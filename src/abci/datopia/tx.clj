(ns abci.datopia.tx
  (:require [abci-host.util
             :refer [b62-encode]]
            [abci-host.ed25519 :as curve]))

(defn sign [tx {pub :public prv :private}]
  (let [tx  {:tx tx :datopia/from (b62-encode pub)}
        sig (curve/sign prv (curve/digest (.getBytes (pr-str tx))))]
    (assoc tx :datopia/signature (b62-encode sig))))
