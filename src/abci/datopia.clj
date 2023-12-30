(ns abci.datopia
  (:require [abci.host            :as host]
            [abci.host.middleware :as mw]
            [datahike.tools       :as d.tools]
            [datahike.api         :as d]
            [datahike.writing
             :refer [create-commit-id]]
            [taoensso.timbre      :as log]
            [abci.datopia.util    :as util]
            [abci.datopia.ed25519 :as curve]
            [abci.datopia.tx      :as tx]
            [clojure.set
             :refer [rename-keys]]
            [clojure.edn])
  (:gen-class))

(log/set-level! :warn)

(def config {:store {:backend :file
                     :path     "/tmp/datahike"}
             :keep-history?      true
             :schema-flexibility :write
             :crypto-hash?       true})

(def schema
  [{:db/ident       :datopia/height
    :db/cardinality :db.cardinality/one
    :db/valueType   :db.type/long}

   {:db/ident       :datopia/identity
    :db/cardinality :db.cardinality/one
    :db/valueType   :db.type/string}

   {:db/ident       :tx/signatory
    :db/cardinality :db.cardinality/one
    :db/valueType   :db.type/string}

   {:db/ident       :tx/signature
    :db/cardinality :db.cardinality/one
    :db/valueType   :db.type/string}

   {:db/ident       :datopia/balance
    :db/cardinality :db.cardinality/one
    :db/valueType   :db.type/long}])

(when-not (d/database-exists? config)
  (d/create-database config)
  (let [conn (d/connect config)]
    (binding [d.tools/get-time-ms (constantly (java.util.Date. 0))]
      (d/transact conn schema))))

(def ^:private conn      (d/connect config))
(def ^:private cur-time  (atom 0))
(def ^:private tx-blocks (atom []))

(defn- cur-hash []
  (-> @conn
      create-commit-id
      str))

(defmulti ^:private respond :stickler/msg)

(defmethod respond :default [_] ::mw/default)

(defmethod respond :abci/RequestInfo [_]
  (if-let [height (d/q '[:find  (max ?height) .
                         :where [_ :datopia/height ?height]] @conn)]
    (let [last-hash (cur-hash)]
      (log/warn "Trying to resume from height" height "with hash" last-hash)
      {:stickler/msg        :abci/ResponseInfo
       :last-block-height   height
       :last-block-app-hash (.getBytes ^String (cur-hash) "UTF-8")})
    ::mw/default))

(defmethod respond :abci/RequestInitChain [{time :time}]
  (reset! cur-time (* (:seconds time) 1000))
  ::mw/default)

(defmethod respond :abci/RequestBeginBlock [{{:keys [time height]} :header}]
  (reset! cur-time (* (:seconds time 1000)))
  (swap! tx-blocks conj [[:db/add -1 :datopia/height height]])
  ::mw/default)

(defn- bytes->tx [tx-bytes]
  ;; implement bytes -> String base62 decode
  (-> tx-bytes String. util/b62-decode String. clojure.edn/read-string))

(defn- tx->code [tx]
  (let [pub (util/b62-decode (:datopia/from tx))
        sig (util/b62-decode (:datopia/signature tx))
        tx  (dissoc tx :datopia/signature)]
    (log/warn "Tx received is" tx)
    (if (curve/signed? pub (curve/digest (.getBytes (pr-str tx))) sig)
      0
      (do
        (log/error "Bad transaction signature")
        1))))

(defn- tx-datoms [tx]
  (let [synth  [[:db/add -1 :tx/signatory     (:datopia/from      tx)]
                [:db/add -1 :tx/signature     (:datopia/signature tx)]
                [:db/add -1 :datopia/identity (:datopia/from      tx)]]]
    (into synth
      (:datoms tx))))

(defmethod respond :abci/RequestDeliverTx [{tx :tx}]
  (let [tx   (bytes->tx tx)
        code (tx->code  tx)]
    (when (zero? code)
      (swap! tx-blocks conj (tx-datoms tx)))
    {:stickler/msg :abci/ResponseDeliverTx
     :code          code}))

(defmethod respond :abci/RequestCheckTx [{tx :tx}]
  (let [tx (bytes->tx tx)]
    {:stickler/msg :abci/ResponseCheckTx
     :code         (tx->code tx)}))

(defmethod respond :abci/RequestCommit [_]
  (binding [d.tools/get-time-ms (constantly (java.util.Date. ^long @cur-time))]
    (doseq [tx-block @tx-blocks]
      (d/transact conn tx-block)))
  (swap! tx-blocks empty)
  {:stickler/msg :abci/ResponseCommit
   :data          (.getBytes ^String (cur-hash) "UTF-8")})

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
