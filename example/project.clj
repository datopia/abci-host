(defproject io.datopia/abci-example "0.1.0-SNAPSHOT"
  :description  "Tendermint ABCI example application."
  :url          "https://github.com/datopia/abci-example"
  :license      {:name "MIT License"
                 :url  "http://opensource.org/licenses/MIT"}
  :scm          {:name "git"
                 :url  "https://github.com/datopia/abci-example"}
  :aot          [abci.example.kv]
  :dependencies [[org.clojure/clojure "1.10.0"]
                 [io.datopia/abci     "0.1.1-SNAPSHOT"]
                 [io.nervous/sputter  "0.1.0"]]
  :profiles     {:kv  {:main abci.example.kv}}
  :aliases      {"kv" ["with-profile" "+kv" "run" "-m" "abci.example.kv"]})
