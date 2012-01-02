(ns elephantdb.common.database
  (:require [hadoop-util.core :as h]
            [jackknife.core :as u]
            [jackknife.logging :as log]
            [elephantdb.common.domain :as domain]
            [elephantdb.common.status :as status])
  (:import [elephantdb.persistence Shutdownable]))

;; ## Database Manipulation Functions

(defn- domain-path
  "Returns the root path that should be used for a domain with the
  supplied name located within the supplied elephantdb root
  directory."
  [local-root domain-name]
  (str local-root "/" domain-name))

(defn domain-get
  "Retrieves the requested domain (by name) from the supplied
  database."
  [{:keys [domains]} domain-name]
  (get domains domain-name))

(defn attempt-update!
  "If an update is available, updates the named domain and hotswaps
   the new version. Returns a future."
  [database domain-name & {:keys [throttle]}]
  (future
    (when-let [domain (domain-get database domain-name)]
      (domain/attempt-update! domain :throttle throttle))))

(defn update-all!
  "If an update is available on any domain, updates the domain's
  shards from its remote store and hotswaps in the new versions."
  [database & {:keys [throttle]}]
  (future
    (u/do-pmap #(domain/attempt-update! % :throttle throttle)
               (vals (:domains database))))
  (u/with-ret true
    (future
      (u/do-pmap #(dom/attempt-update! % :throttle throttle)
                 (vals domain-map)))))

(defn fully-loaded?
  [{:keys [domains]}]
  (every? (some-fn status/ready? status/failed?)
          (vals domains)))

(defn some-updating?
  [{:keys [domains]}]
  (let [domains (vals domains)]
    (some status/loading? (map status/status domains))))

(defn domain->status
  "Returns a map of domain name -> status."
  [{:keys [domains]}]
  (u/val-map status/status domains))

(def domain-names
  "Returns a sequence of all domain names for which the supplied
   database is responsible."
  (comp keys :domains))

(defn purge-unused-domains!
  "Walks through the supplied local directory, recursively deleting
   all directories with names that aren't present in the supplied
   `domains`."
  [local-root name-seq]
  (letfn [(domain? [path]
            (and (.isDirectory path)
                 (not (contains? (into #{} name-seq)
                                 (.getName path)))))]
    (u/dofor [domain-path (-> local-root h/mk-local-path .listFiles)
              :when (domain? domain-path)]
             (log/info "Destroying un-served domain at: " domain-path)
             (h/delete (h/local-filesystem)
                       (.getPath domain-path)
                       true))))

;; ## Database Creation
;;
;; A "database" is the initial configuration map with much more detail
;; about each individual domain. The `build-database` function swaps
;; out each domain's remote path for a populated `Domain` record with
;; all 

(defprotocol Preparable
  (prepare [_] "Perform preparatory steps."))

(defrecord Database [options domains]
  Preparable
  (prepare [{:keys [options domains]}]
    (future
      (purge-unused-domains! (:local-root options)
                             (keys domains))
      (doseq [domain (vals domains)]
        (domain/boot-domain! domain))))

  Shutdownable
  (shutdown [this]
    (log/info "ElephantDB received shutdown notice...")
    (doseq [^Shutdownable domain (vals (:domains this))]
      (.shutdown domain))))

(defn build-database
  [{:keys [domains hosts replication local-root hdfs-conf] :as conf-map}]
  (let [rw-lock (u/mk-rw-lock)]
    (Database. (dissoc conf-map :domains)
               (u/update-vals
                domains
                (fn [domain-name remote-path]
                  (let [local-path (domain-path local-root domain-name)]
                    (domain/build-domain
                     local-root hdfs-conf remote-path hosts replication rw-lock)))))))

;; A full database ends up looking something like the commented out
;; block below. Previously, a large number of functions would try and
;; update all databases at once. With Clojure's concurrency mechanisms
;; we can treat each domain as its own thing and dispatch futures to
;; take care of each in turn.

(comment
  {:replication 1
   :port 3578
   :download-rate-limit 1024
   :local-root "/Users/sritchie/Desktop/domainroot"
   :hosts ["localhost"]
   :hdfs-conf {"fs.default.name"
               "hdfs://hadoop-devel-nn.local.twitter.com:8020"}
   :blob-conf {"fs.default.name"
               "hdfs://hadoop-devel-nn.local.twitter.com:8020"}
   :domains {"graph" {:remote-store <remote-domain-store>
                      :local-store <local-domain-store>
                      :serializer <serializer>
                      :status <status-atom>
                      :shard-index {:hosts->shards {}
                                    :shard->host {}}
                      :domain-data (atom {:version 123534534
                                          :shards {1 <persistence>
                                                   3 <persistence>}})}

             "docs" {:remote-store <remote-domain-store>
                     :local-store <local-domain-store>
                     :serializer <serializer>
                     :status <status-atom>
                     :shard-index {:hosts->shards {}
                                   :shard->host {}}
                     :domain-data (atom {:version 123534534
                                         :shards {1 <persistence>
                                                  3 <persistence>}})}}})
