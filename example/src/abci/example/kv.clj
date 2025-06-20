(ns abci.example.kv
  "An example ABCI application, in the mold of
   Tendermint's [kvstore.go](https://github.com/tendermint/tendermint/blob/master/abci/example/kvstore/kvstore.go).

   The application deterministically persists arbitrary EDN data structures
  under keyword keys --- transactions are literal EDN maps containing one or
  more keys for insertion.  There is no notion of ownership, or user identity
  --- we're mostly trying to hammer down the ABCI interaction details, and hint
  at how a more complex system might be approached.

   The application state is maintained in
   a [Merkle tree](https://en.wikipedia.org/wiki/Merkle_tree) -- specifically, an
   implementation of [Ethereum's Compact Merkle
   Trie](https://nervous.io/clojure/crypto/2018/04/04/clojure-evm-iii/)
   from [io.nervous/sputter](https://github.com/nervous-systems/sputter), a
   Clojure implementation of the Ethereum Virtual Machine.

   This example application has nothing to do with Ethereum --- we had to use
   _some_ authenticated key/value store, and there aren't many to choose from."
  (:require [abci.host                :as host]
            [abci.host.middleware     :as mw]
            [sputter.state.trie       :as trie]
            [sputter.state.kv         :as kv]
            [sputter.state.kv.leveldb :as leveldb]
            [sputter.util
             :refer [bytes->hex]]
            [abci.example.impl.util   :as util])
  (:gen-class))

;; Our state trie requires a backing store which complies with the
;; [[kv/KeyValueStore]] protocol --- we're going with LevelDB, as we'd like
;; the trie state to persist across restarts.

(let [path (str "abci.example.kv-"
                (or (System/getenv "ABCI_PORT")
                    host/default-port))]
  (defonce ^:private store
    (do
      (binding [*out* *err*]
        (println "Using LevelDB Store at" path))
      (leveldb/map->LevelDBStore {:path path}))))

(defn- map->KVTrie
  "Utility for constructing tries backed by [[store]], optionally merging in the
  given options map."
  [& [opts]]
  (trie/map->KVTrie (merge {:store store} opts)))

;; The `trie` atom holds a [[trie/Trie]] - an implementation of Ethereum's compact
;; Merkle trie (or "Merkle Patricia trie")

(defonce ^:private trie (atom (map->KVTrie)))

;; Values are incorporated via `trie/insert` --- an in-memory operation, returning
;; a new trie:

(comment
  (swap! trie trie/insert "key" "value"))

;; `trie/commit` flushes pending inserts to disk, returning a trie holding
;; a `:hash` key --- a recursive cryptographic hash of the trie's contents,
;; which is the identity of the root node.
;;
;; The trie implementation supports only `String`/bytes for keys & values,
;; while our example application supports keyword keys, with arbitrary EDN
;; values.  To bridge this gap, we'll stringify all keys, and print values to
;; EDN strings.  For example, the mapping `:a/b` -> `{:c 1}`, would be
;; translated into `"a/b" -> `(pr-str {:c 1})` prior to insertion.
;;
;; When constructing a trie, we optionally provide a `:root` key, identifying
;; the hash of a node in the underlying store.  As nodes aren't purged, we can
;; time-travel by specifying a stale root hash, resulting in a consistent
;; snapshot of the application's state at that point/block.

(comment
  (map->KVTrie {:root (<bytes> "DAEA9...")}))

;; We'll persist the most recent root hash _directly in `store`_, rather than
;; `trie` --- otherwise we'd have a chicken/egg problem when reconstructing a trie
;; from the most recently committed root hash.

(def ^:private last-hash-k (.getBytes "abci.example.kv/hash" "UTF-8"))

;; The most recent block height _is_ stored within the trie.  As each block'll
;; contain at least one write (the block height adjustment) --- our root hash will
;; change with every block commit.

(def ^:private height-k "abci.example.kv/height")

;; `org.datopia/abci` ensures messages received from Tendermint'll be parsed by
;; `org.datopia/stickler`, a general purpose protobuf3 library.
;;
;; Each message we receive from `org.datopia/abci` is represented as a map, and
;; retains the `:stickler/msg` key identifying its underlying protobuf message
;; type. Rather than use an unwieldy `case` statement --- or similar --- to
;; distinguish between incoming message types, we define a multimethod
;; dispatching on the `:stickler/msg` key.

(defmulti ^:private respond :stickler/msg)

;; [[mw/wrap-default]] is response middleware which expands the keyword
;; `:abci.host.middleware/default` into a generic "success" response map
;; appropriate to the incoming request, leaving all other values untouched.
;; Given that we may not want to special-case every possible message type, we'll
;; use that keyword as our `respond` multimethod's default value.

(defmethod respond :default [_] ::mw/default)

;; `RequestInfo` is integral to the ABCI handshake --- it's the first message
;; we'll receive on startup, when resuming validation of an existing chain.  The
;; ABCI client wants to determine our last known block height (and the corresponding
;; state hash) so it knows which blocks to replay.  If we return the default response,
;; all blocks'll be replayed.

;; We know that we're in a position to replay if there's a value for `last-hash-k`
;; within our data store.  If so, we reconstitute a trie pointing at the hash (which
;; identifies a root node), and lookup the block height within it.

(defmethod respond :abci/RequestInfo [_]
  (if-let [hash (kv/retrieve store last-hash-k)]
    (let [trie' (reset! trie (map->KVTrie {:root hash}))]
      (binding [*out* *err*]
        (println "Resuming from root hash" (bytes->hex hash)))
      {:stickler/msg        :abci/ResponseInfo
       :last-block-app-hash hash
       :last-block-height   (util/edn-value trie' height-k)})
    ::mw/default))

;; Use [[util/valid-tx?]] to determine the `:code` value for the outgoing
;; `ResponseCheckTx` map.

(defmethod respond :abci/RequestCheckTx [{tx :tx}]
  {:stickler/msg :abci/ResponseCheckTx
   :code          (if (util/valid-tx? tx)
                    :abci.code/ok
                    :abci.code/error)})

;; We return the sequence of transactions unmolested, otherwise we
;; would be evicting all of them.

(defmethod respond :abci/RequestPrepareProposal [{txs :txs}]
  {:stickler/msg :abci/ResponsePrepareProposal
   :txs           txs})

(defmethod respond :abci/RequestProcessProposal [_]
  {:stickler/msg :abci/ResponseProcessProposal
   :status       :ACCEPT})

(defn- tx-map->inserts
  "Apply [[util/key->str]] to all keys in `m`, and `pr-str` to all values."
  [m]
  (into {}
    (for [[k v] m]
      [(util/key->str k) (pr-str v)])))


;; Commit a block, flushing trie writes (block height increase & any
;; inserts) to disk.  Ideally we wouldn't commit until RequestCommit,
;; but we have no way of coming up with a valid app hash without
;; committing the trie changes.  Similarly, we could speculatively
;; insert the height in RequestPrepareProposal, and commit it in
;; RequestCommit, however while not documented AFAICT, the first block
;; is finalized without a proposal, so if it's empty, we'd be trying
;; to commit an empty trie.  With a different data structure these
;; things might not be problems.

(defmethod respond :abci/RequestFinalizeBlock [{txs :txs height :height}]
  (let [inserts      (apply merge (map tx-map->inserts (map util/bytes->edn txs)))
        {hash :hash} (swap! trie #(as-> % t
                                    (trie/insert t height-k (pr-str height))
                                    (reduce-kv trie/insert t inserts)
                                    (trie/commit t)))]
    (kv/insert store last-hash-k hash)
    {:stickler/msg :abci/ResponseFinalizeBlock
     :app-hash      hash}))

;; `RequestQuery`'s `:data` key ought to hold a byte array representation of
;; a keyword, which we'll lookup in a clean trie constructed around the hash
;; for the last committed block.  While the ABCI API allows queries at any
;; height, the official `kvstore.go` example foregoes this --- as will we.
;; In another concession to brevity, we'll not include Merkle proofs in our
;; responses --- they're irrelevant to application structure.
;;
;; [[trie/search]] returns `nil` if the key is non-existent;
;; the user'll be unable to distinguish between absent keys and explicit `nil`
;; values --- but we don't really care.

(defn- query [in-k]
  (let [k (util/bytes->edn in-k)]
    (when (and (keyword? k) (not= k ::util/invalid))
      (when-let [hash (kv/retrieve store last-hash-k)]
        (let [committed (map->KVTrie {:root hash})]
          {::value (trie/search committed (util/key->str k))})))))

(defmethod respond :abci/RequestQuery [{in-k :data :as m}]
  (if-let [m (query in-k)]
    {:stickler/msg :abci/ResponseQuery
     :code         :abci.code/ok
     :key          in-k
     :value        (::value m)}
    {:stickler/msg :abci/ResponseQuery
     :code         :abci.code/error}))

(defn- wrap-handler
  "Wrap `handler` with application-appropriate middleware.
   Return a new handler fn."
  [handler]
  (-> handler
      mw/wrap-synchronous
      mw/wrap-default
      mw/wrap-envelope
      mw/wrap-code-keywords))

(defn -main [& args]
  (let [port (some-> (System/getenv "ABCI_PORT") Integer/parseInt)]
    (host/start (wrap-handler respond) (when port {:port port}))))
