(let [url "https://github.com/datopia/abci-host/"]
  (defproject org.datopia/abci "0.1.0"
    :description  "Clojure host/server for Tendermint's ABCI protocol."
    :url          ~url
    :license      {:name "MIT License"
                   :url  "http://opensource.org/licenses/MIT"}
    :scm          {:name "git"
                   :url  ~url}
    :dependencies [[org.clojure/clojure             "1.11.1"]
                   [aleph                           "0.4.6"]
                   [org.datopia/stickler-codec      "0.1.1"]
                   [org.bouncycastle/bcprov-jdk15on "1.61"]
                   [io.replikativ/datahike          "0.6.1555"]
                   [com.taoensso/timbre             "6.3.1"]
                   [clj-http                        "3.12.3"]]
    :profiles
    {:dev
     {:dependencies [[org.datopia/stickler-translate "0.1.1"]
                     [io.datopia/codox-theme         "0.1.0"]
                     [org.clojure/test.check         "0.10.0-alpha3"]]
      :plugins      [[lein-codox                     "0.10.5"]
                     [org.datopia/lein-stickler      "0.1.0"]]
      :codox        {:namespaces [#"^abci\.(?!impl)"]
                     :metadata   {:doc/format :markdown}
                     :themes     [:default [:datopia {:datopia/github ~url}]]}
      :global-vars  {*warn-on-reflection* true}}}))
