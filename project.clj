(let [url "https://github.com/datopia/abci-host/"]
  (defproject io.datopia/abci "0.1.1-SNAPSHOT"
    :description  "Clojure host/server for Tendermint's ABCI protocol."
    :url          ~url
    :license      {:name "MIT License"
                   :url  "http://opensource.org/licenses/MIT"}
    :scm          {:name "git"
                   :url  ~url}
    :dependencies [[org.clojure/clojure       "1.10.0"]
                   [aleph                     "0.4.6"]
                   [io.datopia/stickler-codec "0.1.0"]]
    :profiles
    {:dev
     {:dependencies [[io.datopia/stickler-translate "0.1.0"]
                     [io.datopia/codox-theme        "0.1.0"]
                     [org.clojure/test.check        "0.10.0-alpha3"]]
      :plugins      [[lein-codox "0.10.5"]]
      :codox        {:namespaces [#"^abci\.(?!impl)"]
                     :metadata   {:doc/format :markdown}
                     :themes     [:default [:datopia {:datopia/github ~url}]]}
      :global-vars  {*warn-on-reflection* true}
      :aliases
      {"gen-protobuf" ["run" "-m" "abci.impl.gen-protobuf"]}}}))
