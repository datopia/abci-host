(ns abci.datopia
  (:require [abci.host            :as host]
            [abci.host.middleware :as mw]
            [datahike.tools       :as d.tools]
            [datahike.api         :as d]
            [konserve.core        :as k]
            [hasch.core           :as h]
            [taoensso.timbre      :as log])
  (:gen-class))

(log/set-level! :warn)

(def config {:store {:backend :file
                     :path     "/tmp/datahike-sandbox"}
             :keep-history?      true
             :schema-flexibility :write
             :crypto-hash?       true
             :attribute-refs?    true})

(def schema
  [{:db/ident       :datopia/height
    :db/cardinality :db.cardinality/one
    :db/valueType   :db.type/long}])

(when-not (d/database-exists? config)
  (d/create-database config)
  (let [conn (d/connect config)]
    (binding [d.tools/get-time-ms (constantly (java.util.Date. 0))]
      (d/transact conn schema))))

(def ^:private conn     (d/connect config))
(def ^:private cur-time (atom 0))
(def ^:private tx-block (atom []))

(defn- cur-hash []
  (-> @conn
      :store
      (assoc :read-handlers (atom {}))
      (k/get :db nil {:sync? true})
      (dissoc :config)
      h/uuid
      str))

(defmulti ^:private respond :stickler/msg)

(defmethod respond :default [_] ::mw/default)

(defmethod respond :abci/RequestInfo [_]
  (if-let [height (d/q '[:find  (max ?height) .
                         :where [_ :datopia/height ?height]] @conn)]
    (let [last-hash (cur-hash)]
      (binding [*out* *err*]
        (println "Trying to resume from height" height "with hash" last-hash))
      {:stickler/msg        :abci/ResponseInfo
       :last-block-height   height
       :last-block-app-hash (.getBytes ^String (cur-hash) "UTF-8")})
    ::mw/default))

(defmethod respond :abci/RequestInitChain [{time :time}]
  (reset! cur-time (* (:seconds time) 1000))
  ::mw/default)

(defmethod respond :abci/RequestBeginBlock [{{:keys [time height]} :header}]
  (reset! cur-time (* (:seconds time 1000)))
  (swap! tx-block conj {:db/id -1 :datopia/height height})
  ::mw/default)

(defmethod respond :abci/RequestCommit [_]
  (binding [d.tools/get-time-ms (constantly (java.util.Date. @cur-time))]
    (d/transact conn @tx-block))
  (swap! tx-block empty)
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
