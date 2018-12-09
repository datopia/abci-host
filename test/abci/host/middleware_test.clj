(ns abci.host.middleware-test
  (:require [abci.host.middleware :as mw]
            [abci.host            :as host]
            [clojure.test :refer [deftest is]]))

(defn- all-requests [f]
  (into {}
    (for [[field-k field] (-> host/schema :abci/Request :fields)
          :let [req  {:stickler/msg          :abci/Request
                      :stickler.one-of/value field-k
                      field-k                {:stickler/msg (:type field)}}
                resp @(f req)]]
      [(:type field) resp])))

(deftest wrap-default-positive
  (let [f (-> (constantly ::mw/default) mw/wrap-synchronous mw/wrap-default)]
    (doseq [[t resp] (all-requests f)]
      (is (= (:stickler/msg resp) :abci/Response)))))

(deftest wrap-default-negative
  (let [exp {::key ::value}
        f   (-> (constantly exp) mw/wrap-synchronous mw/wrap-default)]
    (doseq [[t resp] (all-requests f)]
      (is (= exp resp)))))

(let [resp {:stickler/msg :abci/Response
            :deliver-tx   {:stickler/msg :abci/ResponseDeliverTx
                           :code         :abci.code/ok}}]
  (deftest wrap-code-keywords-positive
    (let [f (-> (constantly resp) mw/wrap-synchronous mw/wrap-code-keywords)]
      (is (-> @(f nil) :deliver-tx :code (= host/CODE-OK)))))

  (deftest wrap-code-keywords-negative
    (let [resp (assoc-in resp [:deliver-tx :code] host/CODE-OK)
          f    (-> (constantly resp) mw/wrap-synchronous mw/wrap-code-keywords)]
      (is (-> @(f nil) :deliver-tx :code (= host/CODE-OK))))))

(let [f (-> identity mw/wrap-synchronous mw/wrap-request-envelope)]
  (deftest wrap-request-envelope-positive
    (doseq [[t resp] (all-requests f)]
      (is (= t (:stickler/msg resp)))))

  (deftest wrap-request-envelope-negative
    (let [f (mw/wrap-request-envelope f)]
      (doseq [[t resp] (all-requests f)]
        (is (= t (:stickler/msg resp)))))))

(let [f (-> #(mw/default-response % {:envelop? false})
            mw/wrap-synchronous
            mw/wrap-response-envelope)]
  (deftest wrap-response-envelope-positive
    (doseq [[t resp] (all-requests f)]
      (is (= :abci/Response (:stickler/msg resp)))))

  (deftest wrap-response-envelope-negative
    (let [f (mw/wrap-default f)]
      (doseq [[t resp] (all-requests f)]
        (is (= :abci/Response (:stickler/msg resp)))))))

(deftest wrap-default+envelope
  (let [f (-> (fn [req]
                (case (:stickler/msg req)
                  :abci/Request          (throw (ex-info "Enveloped value." req))
                  :abci/RequestDeliverTx {:stickler/msg :abci/ResponseDeliverTx}
                  ::mw/default))
              mw/wrap-synchronous
              mw/wrap-default
              mw/wrap-envelope)]
    (doseq [[t resp] (all-requests f)]
      (is (= :abci/Response (:stickler/msg resp))))))
