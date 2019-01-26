(ns abci.host.middleware
  "Utility functions for transforming ABCI handler functions.  All middleware
   assumes asynchronous handler functions, excepting [[wrap-synchronous]]."
  (:require [clojure.string     :as str]
            [clojure.walk       :as walk]
            [manifold.deferred  :as d]
            [manifold.executor]
            [abci.host          :as host :refer [schema]]))

(defn wrap-synchronous
  "Wrap `f` such that calls to it are initiated from a worker thread, using
   [future-with](https://aleph.io/codox/manifold/manifold.deferred.html#var-future-with).
   The wrapped function will always return a deferred.

   The default executor may be overridden by providing `:executor` in `opts`."
  [f & [opts]]
  (let [exec (or (:executor opts)
                 (manifold.executor/execute-pool))]
    (fn wrapped-blocking-handler [req]
      (d/future-with exec (f req)))))

(def ^:private req->null-response
  (into {}
    (for [[field-k field] (-> schema :abci/Request :fields)
          :let [type-k    (:type field)
                resp-name (str/replace (name type-k) "Request" "Response")
                resp-k    (keyword "abci" resp-name)]]
      [type-k {:stickler/msg          :abci/Response
               :stickler.one-of/value field-k
               field-k                {:stickler/msg resp-k}}])))

(def ^:private req->default-response
  (-> req->null-response
      (assoc-in [:abci/RequestDeliverTx :deliver-tx :code]    host/CODE-OK)
      (assoc-in [:abci/RequestCheckTx   :check-tx   :code]    host/CODE-OK)
      (assoc-in [:abci/RequestQuery     :query      :code]    host/CODE-OK)
      (assoc-in [:abci/RequestInfo      :info       :data]    "NO_INFO")
      (assoc-in [:abci/RequestEcho      :echo       :message] "NOECHO")))

(defn- open-envelope [m]
  {:pre [(map? m)] :post [(map? %)]}
  (get m (:stickler.one-of/value m)))

(defn default-response
  "Convenience.  Return the default response for the given request keyword, the
   `:stickler/msg` value of the given map, or the `:stickler/msg` key of its
   `:stickler.one-of/value`, when enveloped.

   Result will be stripped of its `:abci/Response` envelope when not
  `:envelop?`.

  ```clojure
  (default-response :abci/RequestDeliverTx)
  (default-response {:stickler/msg :abci/RequestDeliverTx ...})
  (default-response {:stickler/msg          :abci/Request
                     :stickler.one-of/value :deliver-tx
                     :deliver-tx            {:stickler/msg :abci/RequestDeliverTx ...}})
  ```"
  [v & [{envelop? :envelop? :or {envelop? true}}]]
  {:post [(map? %)]}
  (let [v (-> v
              (cond-> (not (keyword? v)) :stickler/msg)
              (as-> v' (case v'
                         :abci/Request (:stickler/msg (open-envelope v))
                         v')))]
    (when-not (keyword? v)
      (throw (ex-info "Invalid input." {:input v})))
    (cond-> (req->default-response v) (not envelop?) open-envelope)))

(defn wrap-default
  "If the deferred returned by `f` resolves to `::default`, substitute it for a
   request-appropriate success response.

   If `opts` contains a `:predicate` key, its value will be used over `(partial
   identical? ::default)` when determining whether to substitute a return value."
  [f & [opts]]
  (let [pred? (or (:predicate opts) (partial identical? ::default))]
    (fn wrapped-default-response [req]
      (d/let-flow [resp (f req)]
        (if (pred? resp)
          (let [req (try
                      (open-envelope req)
                      (catch Throwable _
                        req))]
            (if-let [resp (req->default-response (:stickler/msg req))]
              resp
              (throw (ex-info "No default response." {:req req}))))
          resp)))))

(defn wrap-prewalk-replace
  "Wrap `f` such that the structure its deferred resolves to is passed
   to `walk/prewalk-replace`, with the given `substitutions` map."
  [f substitutions]
  (let [substitute (partial walk/prewalk-replace substitutions)]
    (fn wrapped-prewalk-replace [req]
      (-> (f req)
          (d/chain substitute)))))

(let [k->code {:abci.code/ok    host/CODE-OK
               :abci.code/error host/CODE-ERROR}]
  (defn wrap-code-keywords
    "Wrap `f` such that the structure its deferred resolves to is walked,
     substituting keywords in the `abci.code` namespace (`:abci.code/ok`,
     `:abci.code/error`) with the corresponding numbers (0, 1)."
    [f]
    (wrap-prewalk-replace f k->code)))

(defn- lift-keys [from to pred]
  (if-not pred
    to
    (merge (into {}
             (for [[k v] from :when (pred k)]
               [k v])) to)))

(defn wrap-request-envelope
  "Wrap `f` such that it receives the `:stickler.one-of/value` map for each
   incoming `:abci/Request`, rather than the request map itself.

   Pass-through values which don't appear to be enveloped."
  [f & [{lift-key? :lift-key? :or {lift-key? #{::host/conn}}}]]
  (fn wrapped-request-envelope [req]
    (if-let [k (:stickler.one-of/value req)]
      (let [m (k req)]
        (f (lift-keys req m lift-key?)))
      (f req))))

(let [msg->response-k
      (into {}
        (for [[field-k field] (-> host/schema :abci/Response :fields)]
          [(:type field) field-k]))]
  (defn wrap-response-envelope
    "Wrap `f` such that its eventual result is wrapped in an `:abci/Response`.

    Pass-through values which appear enveloped."
    [f & [{lift-key? :lift-key? :or {lift-key? nil}}]]
    (fn wrapped-response-envelope [req]
      (d/let-flow [resp (f req)]
        (let [msg (:stickler/msg resp)]
          (when-not (keyword? msg)
            (throw (ex-info "No :stickler/msg key." {:resp resp})))
          (if (identical? msg :abci/Response)
            resp
            (let [k (msg msg->response-k)]
              (lift-keys resp {:stickler/msg :abci/Response
                               k             resp} lift-key?))))))))

(defn wrap-envelope
  "Use both [[wrap-request-envelope]] and [[wrap-response-envelope]] with their
   default configuration."
  [f]
  (-> f wrap-request-envelope wrap-response-envelope))
