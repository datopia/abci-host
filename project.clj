(let [url "https://github.com/datopia/abci-host/"]
  (defproject org.datopia/abci "0.1.0"
    :description  "Clojure host/server for Tendermint's ABCI protocol."
    :url          ~url
    :license      {:name "MIT License"
                   :url  "http://opensource.org/licenses/MIT"}
    :scm          {:name "git"
                   :url  ~url}
    :dependencies [[org.clojure/clojure        "1.10.0"]
                   [aleph                      "0.4.6"]
                   [org.datopia/stickler-codec "0.1.1"]]
    :profiles
    {:dev
     {:dependencies [[org.datopia/stickler-translate "0.1.2-SNAPSHOT"]
                     [io.datopia/codox-theme         "0.1.0"]
                     [org.clojure/test.check         "0.10.0-alpha3"]]
      :plugins      [[lein-codox                     "0.10.5"]
                     [org.datopia/lein-stickler      "0.1.0-SNAPSHOT"]]
      :codox        {:namespaces [#"^abci\.(?!impl)"]
                     :metadata   {:doc/format :markdown}
                     :themes     [:default [:datopia {:datopia/github ~url}]]}
      :global-vars  {*warn-on-reflection* true}}}))
