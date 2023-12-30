(ns abci.datopia.tx
  (:require [abci.datopia.util
             :refer [b62-encode]]
            [abci.datopia.ed25519 :as curve]
            [clj-http.client      :as client]
            [taoensso.timbre      :as log]))

(defn envelop [tx {pub :public prv :private}]
  (let [tx  {:tx tx :datopia/from (b62-encode pub)}
        sig (curve/sign prv (curve/digest (.getBytes (pr-str tx))))]
    (assoc tx :datopia/signature (b62-encode sig))))

(let [uri "http://localhost:26657/broadcast_tx_commit"]
  (defn localnode-submit! [signed-tx]
    (log/warn "Tx sent is" (dissoc signed-tx :datopia/signature))
    (let [tx (b62-encode (.getBytes (pr-str signed-tx)))]
      ;; goofy interface
      (client/get uri {:query-params {"tx" (str "\"" tx "\"")}}))))

(comment
  (defonce keypair (curve/generate-keypair))

  (def tx (envelop {:xyz 1234} keypair))

  (localnode-submit! tx))
