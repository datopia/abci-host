(ns abci.impl.gen-protobuf
  "Utility script to synchronize EDN ABCI schema w/ jtendermint's."
  (:require [stickler.translate :as stickler]
            [clojure.pprint     :as pprint]
            [clojure.java.io    :as io])
  (:import [java.nio.file Files Paths CopyOption StandardCopyOption]
           [java.nio.file.attribute FileAttribute])
  (:gen-class))

(def ^:private default-output "resources/io.datopia.abci/abci.edn")

(defn- segments->Path [[h & t]]
  (Paths/get h (into-array String t)))

(let [copy-opts (into-array
                 CopyOption
                 [StandardCopyOption/COPY_ATTRIBUTES
                  StandardCopyOption/REPLACE_EXISTING])]
  (defn- copy-dep [^java.nio.file.Path dir {in :in out :out}]
    {:pre [(seq in) (seq out)]}
    (let [in  (segments->Path in)
          out (.resolve dir (segments->Path out))]
      (when-let [parent (.getParent out)]
        (.mkdirs (.toFile parent)))
      (Files/copy in out copy-opts))))

(let [t-ns "com.github.jtendermint.jabci.types"]
  (defn- dirs->edn-schema [& dirs]
    (-> (apply stickler/dirs->Schema dirs)
        (stickler/prune-Schema {:include [(str t-ns ".ABCIApplication")
                                          (str t-ns ".Request")
                                          (str t-ns ".Response")]})
        stickler/Schema->edn
        (stickler/rename-packages {t-ns "abci"}))))

(let [deps #{{:in  ["submodules" "jtendermint" "src" "main" "proto" "types.proto"]
              :out ["tendermint.proto"]}
             {:in  ["submodules" "protobuf" "src" "google" "protobuf" "timestamp.proto"]
              :out ["google" "protobuf" "timestamp.proto"]}}]
  (defn -main [& [out-f]]
    (let [attrs  (make-array FileAttribute 0)
          tmp-in (Files/createTempDirectory "proto" attrs)]
      (doseq [dep deps]
        (copy-dep tmp-in dep))
      (let [edn-schema (dirs->edn-schema (str tmp-in))]
        (.delete (.toFile tmp-in))
        (with-open [w (io/writer (or out-f default-output))]
          (pprint/write edn-schema :stream w))))))
