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
    :refer [long->varint64]]
   [abci.impl.host
    :refer [stream-splitter]]))

(def ^{:doc "Integer success `:code`. See [[abci.host.middleware/wrap-code-keywords]]."}
  CODE-OK        0)
(def ^{:doc "Integer failure `:code`. See [[abci.host.middleware/wrap-code-keywords]]."}
  CODE-ERROR     1)

(def ^{:doc "Default host port.  See [[start]]'s `opts` map."}
  default-port 26658)

(def ^:no-doc schema
  (with-open [r (-> (io/resource "org.datopia.abci/abci.edn")
                    io/reader
                    java.io.PushbackReader.)]
    (codec/prepare-schema (edn/read r))))

(def ^:private decode-bytes (partial codec/decode-bytes schema))
(def ^:private encode-bytes (partial codec/encode-bytes schema))
(def ^:private drained      ::drained)
(def ^:private drained?     (partial identical? drained))

(defn- respond! [s resp]
  (let [^bytes bs (encode-bytes resp)]
    (s/put-all! s [(long->varint64 (alength bs)) bs])))

(defn- req->conn-name [req]
  (let [req ((:stickler.one-of/value req) req)]
    (case (:stickler/msg req)
      :abci/RequestFlush   nil
      :abci/RequestInfo    :info
      :abci/RequestCheckTx :mempool
      :abci/RequestQuery   :query
      :consensus)))

(defn- abci-handler-wrapper [f & [{on-error :on-error :as opts}]]
  (fn [s _]
    (let [msgs (s/stream)]
      (s/connect-via (s/source-only s) (stream-splitter msgs) msgs)
      (d/loop [conn-name nil]
        (-> (s/take! msgs drained)
            (d/chain
             (fn [res]
               (when-not (drained? res)
                 (let [req       (decode-bytes :abci/Request res)
                       conn-name (or conn-name (req->conn-name req))]
                   (-> (f (assoc req ::conn conn-name))
                       (d/chain
                        (partial respond! s)
                        (fn maybe-recur [put-succeeded?]
                          (when put-succeeded?
                            (d/recur conn-name)))))))))
            (d/catch
              (fn [e]
                (when-not on-error
                  (throw e))
                (on-error e))))))))

(defn- on-error [server-atom ^Throwable err]
  (binding [*out* *err*]
    (println "abci.host: Unhandled error, terminating.")
    (.printStackTrace err))
  (when-let [closeable @server-atom]
    (.close ^java.io.Closeable closeable)
    (reset! server-atom nil)))

(def ^:dynamic ^:no-doc *start-server* tcp/start-server)

(defn start
  "Accept Tendermint ABCI connections on the `:port` specified in `opts`,
   or [[default-port]]. Pass incoming messages to `handler` (`map -> Deferred
  map` - see [[abci.host.middleware/wrap-synchronous]]), using its return value
  as the response to each request.

   An `:on-error` key in `opts` may provide a function of (`Closeable`,
  `Throwable`). The default fn prints a stack trace to stderr and closes all
  connections. Not _all_ error conditions trigger `:on-error` --- it's invoked
  only for errors subsequent to the receipt of a correctly length-prefixed
  message over a socket connection. More fundamental errors (e.g. totally
  malformed socket input) will be logged, but not surfaced to the application.

  Return the result of Aleph's [start-server](https://aleph.io/codox/aleph/aleph.tcp.html)."
  [handler & [opts]]
  (let [server   (atom nil)
        on-error (get opts :on-error on-error)
        opts     (as-> opts o
                   (merge {:port default-port} o {:raw-stream? true})
                   (assoc o :on-error (partial on-error server)))
        f        (abci-handler-wrapper handler opts)]
    (reset! server (*start-server* f opts))))

(defn stop
  "Taking the value returned by [[start]], stop the corresponding server."
  [^java.io.Closeable server]
  (.close server))
