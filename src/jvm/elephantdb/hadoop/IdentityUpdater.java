package elephantdb.hadoop;

import elephantdb.persistence.Document;
import elephantdb.persistence.Persistence;

import java.io.IOException;


/**
 * Does what you'd expect and just passes the k-v pairs right on through.
 */
public class IdentityUpdater implements ElephantUpdater {

    public void update(Persistence persistence, Document doc) throws IOException {
        persistence.index(doc);
    }
}