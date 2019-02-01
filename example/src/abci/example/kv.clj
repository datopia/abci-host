(ns abci.example.kv
  (:require [abci.host                :as host]
            [abci.host.middleware     :as mw]
            [sputter.state.trie       :as trie]
            [sputter.state.kv         :as kv]
            [sputter.state.kv.leveldb :as leveldb]
            [abci.example.impl.util   :as util])
  (:gen-class))

;; Our state trie requires a backing store which complies with the
;; [[kv/KeyValueStore]] protocol --- we're going with LevelDB, as we'd like
;; the trie state to persist across restarts.

(defonce ^:private store
  (leveldb/map->LevelDBStore {:path "abci.example.kv"}))

(defn- ->KVTrie
  "Utility for constructing tries backed by [[store]]."
  [& [opts]]
  (trie/map->KVTrie (merge {:store store} opts)))

;; The `trie` atom holds a [[trie/Trie]] - an implementation of Ethereum's compact
;; Merkle trie (or "Merkle Patricia trie").  We're using it as an immutable, disk
;; persistent, authenticated key-value store.

(defonce ^:private trie (atom (->KVTrie)))

;; Values are incorporated  via `trie/insert` --- an in-memory operation, returning
;; a new trie:

(comment
  (swap! trie trie/insert "key" "value"))

;; `trie/commit` flushes pending writes to disk, returning a trie holding
;; a `:hash` key, the recursive cryptographic hash of the committed trie's root
;; node.
;;
;; The trie implementation supports only `String`/bytes for keys & values,
;; while our example application supports only keyword keys, with arbitrary EDN
;; values.  To bridge this gap, we'll stringify all keys, and print values to
;; EDN strings.  For example, the mapping `:a/b` -> `{:c 1}`, would be
;; translated into `"a/b" -> `(pr-str {:c 1})` prior to insertion.
;;
;; When constructing a trie, we optionally provide a `:root` key, identifying
;; the hash of a node in the underlying store.  As nodes aren't purged, we can
;; time-travel by specifying a stale root hash, resulting in a consistent
;; snapshot of the application's state at that point/block.

(comment
  (->KVTrie {:root (<bytes> "DAEA9...")}))

;; We'll persist the most recent root hash _directly in `store`_, rather than
;; `trie` --- otherwise we'd have a chicken/egg problem when reconstructing a trie
;; from the most recently committed root hash.

(def ^:private last-hash-k (.getBytes "abci.example.kv/hash" "UTF-8"))

;; The most recent block height _is_ stored within the trie.  As each block'll
;; contain at least one write (the block height adjustment) --- our root hash will
;; change with every block commit.

(def ^:private height-k "abci.example.kv/height")

;; `io.datopia/abci` ensures messages received from Tendermint'll be parsed by
;; `io.datopa/stickler`, a general purpose protobuf3 library.
;;
;; Each message we receive from `io.datopia/abci` is represented as a map, and
;; retains the `:stickler/msg` key identifying the underlying protobuf message
;; type. Rather than use an unwieldy `case` statement - or similar - to
;; distinguish between incoming message types, we define a multimethod
;; dispatching on the `:stickler/msg` key.

(defmulti ^:private respond :stickler/msg)

;; [[mw/wrap-default]] is response middleware which expands the keyword
;; `:abci.host.middleware/default` into a generic "success" response map
;; appropriate to the incoming request, leaving all other values untouched.
;; Given that we may not want to special-case every possible message type, we'll
;; use that keyword as our `respond` multimethod's default value.

(defmethod respond :default [_] ::mw/default)

;; `RequestInfo` is part of the startup handshake --- it's the
;; first message we'll receive, when resuming validation of an
;; existing chain.  The ABCI client wants to determine our last known block
;; height (and the corresponding state hash), so it knows which blocks to
;; replay.  If  we return the default response, all blocks'll be replayed.

(defmethod respond :abci/RequestInfo [_]
  (if-let [hash (kv/retrieve store last-hash-k)]
    (let [trie' (reset! trie (->KVTrie {:root hash}))]
      {:stickler/msg        :abci/ResponseInfo
       :last-block-app-hash hash
       :last-block-height   (util/edn-value trie' height-k)})
    ::mw/default))

;; When beginning a new block, insert (in memory) the block height into the
;; state trie, returning a default success response.

(defmethod respond :abci/RequestBeginBlock [{header :header}]
  (let [height-v (pr-str (:height header))]
    (swap! trie trie/insert height-k height-v))
  ::mw/default)

;; Use [[util/valid-tx?]] to determine the `:code` value for the outgoing
;; `ResponseCheckTx` map.

(defmethod respond :abci/RequestCheckTx [{tx :tx}]
  {:stickler/msg :abci/ResponseCheckTx
   :code          (if (util/valid-tx? tx)
                    :abci.code/ok
                    :abci.code/error)})

;; `RequestDeliverTx` receives the user-submitted transaction as bytes in its
;; `:tx` key. In our application, this is assumed to be a representation of
;; an EDN map, containing key-value pairs we'll insert into [[trie]].  Note that
;; we don't yet call [[trie/commit]] to flush the changes to disk - this is an
;; in-memory operation, until the block is committed.

(defn- tx-map->inserts
  "Apply [[util/key->str]] to all keys in `m`, and `pr-str` to all values."
  [m]
  (into {}
    (for [[k v] m]
      [(util/key->str k) (pr-str v)])))

(defmethod respond :abci/RequestDeliverTx [{tx :tx}]
  (let [inserts (tx-map->inserts (util/bytes->edn tx))]
    (swap! trie #(reduce-kv trie/insert % inserts)))
  ::mw/default)

;; Commit a block, flushing trie writes (block height increase & any
;; inserts) to disk.

(defmethod respond :abci/RequestCommit [_]
  (let [{hash :hash :as trie} (swap! trie trie/commit)]
    (kv/insert store last-hash-k hash)
    {:stickler/msg :abci/ResponseCommit
     :data          hash}))

;; `RequestQuery`'s `:data` key ought to hold a byte array keyword, which
;; we'll lookup in the trie, as of the last committed block.  [[trie/search]]
;; returns `nil` if the key is non-existent; the user'll be unable to
;; distinguish between absent keys and explicit `nil` values --- but we don't
;; really care.
;;
;; While we can trivially generate Merkle proofs with [[trie/prove]], their
;; shape is slightly different than expected by the ABCI client --- adjusting
;; them isn't worth the clutter for an example application.

;; TODO query

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
  (println "Running.")
  (host/start (wrap-handler respond)))
