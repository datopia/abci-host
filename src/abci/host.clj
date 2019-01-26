(ns abci.host
  "Idiomatic [Tendermint](https://tendermint.com/) ABCI host implementation."
  (:require
   [manifold.deferred :as d]
   [manifold.stream   :as s]
   [clojure.edn       :as edn]
   [aleph.tcp         :as tcp]
   [clojure.java.io   :as io]
   [stickler.codec    :as codec]
   [abci.impl.codec
    :refer [long->signed-varint64]]
   [abci.impl.host
    :refer [stream-splitter]]))

(def ^{:doc "Integer success `:code`. See [[abci.host.middleware/wrap-code-keywords]]."}
  CODE-OK        0)
(def ^{:doc "Integer failure `:code`. See [[abci.host.middleware/wrap-code-keywords]]."}
  CODE-ERROR     1)

(def ^{:doc "Default host port.  See [[start]]'s `opts` map."}
  default-port 26658)

(def ^:no-doc schema
  (with-open [r (-> (io/resource "io.datopia.abci/abci.edn")
                    io/reader
                    java.io.PushbackReader.)]
    (codec/prepare-schema (edn/read r))))

(def ^:private decode-bytes (partial codec/decode-bytes schema))
(def ^:private encode-bytes (partial codec/encode-bytes schema))
(def ^:private drained      ::drained)
(def ^:private drained?     (partial identical? drained))

(defn- respond! [s resp]
  (let [^bytes bs (encode-bytes resp)]
    (s/put-all! s [(long->signed-varint64 (alength bs)) bs])))

(defn- req->conn-name [req]
  (let [req ((:stickler.one-of/value req) req)]
    (case (:stickler/msg req)
      :abci/RequestFlush   nil
      :abci/RequestInfo    :info
      :abci/RequestCheckTx :mempool
      :consensus)))

(defn- abci-handler-wrapper [f & [opts]]
  (fn [s info]
    (let [msg-s     (s/stream)
          conn-name (volatile! nil)]
      (s/connect-via (s/source-only s) (stream-splitter msg-s) msg-s)
      (d/loop []
        (-> (s/take! msg-s drained)
            (d/chain
             (fn [res]
               (when-not (drained? res)
                 (let [req (decode-bytes :abci/Request res)]
                   (when-not @conn-name
                     (vreset! conn-name (req->conn-name req)))
                   (-> (f (assoc req ::conn @conn-name))
                       (d/chain
                        (partial respond! s)
                        #(when %
                           (d/recur)))))))))))))

(defn start
  "Accept Tendermint ABCI connections on the `:port` specified in `opts`,
   or [[default-port]].  Pass incoming messages to `handler` (`map -> Deferred
   map` - see [[abci.host.middleware/wrap-synchronous]]), using its return value
   as the response to each request.

   Return the result of Aleph's [start-server](https://aleph.io/codox/aleph/aleph.tcp.html)."
  [handler & [opts]]
  (let [opts (merge {:port default-port} opts {:raw-stream? true})]
    (tcp/start-server (abci-handler-wrapper handler opts) opts)))
