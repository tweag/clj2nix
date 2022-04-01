(ns clj2nix.core
  (:gen-class)
  (:require [clojure.tools.deps.alpha :as deps]
            [clojure.tools.deps.alpha.util.maven :as mvn]
            [clojure.tools.cli :refer [parse-opts]]
            [clojure.string :as string]
            [clojure.edn :as edn]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.tools.gitlibs.config :as glConfig]
            [clojure.tools.gitlibs.impl :as glImpl]
            [clojure.java.shell :refer [sh]]))

(defn- resolve-artifact-and-group [name]
  (let [split (string/split (str name) #"/")
        [name classifier]
        (if (= 1 (count split))
          (string/split (str name) #"\$")
          (string/split (second split) #"\$"))]
    (if (= 1 (count split))
      [name name classifier]
      [(first split) name classifier])))

(defn- clean-url [url]
  (string/replace-first
    (str (glImpl/git-dir url))
    (str (:gitlibs/dir @glConfig/CONFIG) "/")
    ""))

(defn- resolve-git-sha256 [git-url]
  (let [result  (:out (sh "nix-prefetch-git"
                          "--url" git-url))]
    (get (json/read-str result) "sha256")))

(defn- resolve-sha512 [filepath]
  (assert (.exists (io/as-file filepath))
          (str filepath " " "doesn't exists."))
  (string/trim (:out (sh "nix-hash" "--type" "sha512" "--base32" "--flat" filepath))))

(defn- generate-item [acc [name dep]]
  (let [
    [groupID artifactID classifier] (resolve-artifact-and-group name)
    git-dep?   (contains? dep :git/url)
    local-dep? (contains? dep :local/root)]
    (assert (contains? dep :paths)
            (str name " has not been fetched locally."
                 " Make sure that all dependencies exist locally."))
    (cond
      local-dep? (do (println "Warning: " name
                              " is a local dependency."
                              " All its remote dependencies will "
                              " be resolved, but you need to add the "
                              " jar/dir manually as a source to your "
                              " nix-expression for it to be included."
                              " As well as append its classpath via the "
                              " argument extraClasspaths in "
                              " makeClasspaths if needed.")
                     acc)
      ; TODO: Throw error when :git/tag is specified, because this would fail
      ; at runtime, see https://clojure.atlassian.net/browse/TDEPS-223
      git-dep?  (conj
                  acc
                  {name
                   {:type "git"
                    :dependents (get dep :dependents [])
                    ; Below properties are needed by Nix specifically for Git
                    :url (:git/url dep)
                    :namespace (namespace name)
                    :cleanUrl (clean-url (:git/url dep))
                    :rev (or (:sha dep) (:git/sha dep))
                    :sha256 (resolve-git-sha256 (:deps/root dep))}})
      :else      (conj
                  acc
                  {name
                   {:type "maven"
                    :dependents (get dep :dependents [])
                    ; Below properties are needed by Nix specifically for Maven
                    :artifactId artifactID
                    :groupId groupID
                    :version (:mvn/version dep)
                    :classifier classifier
                    :sha512 (resolve-sha512 (first (:paths dep)))}}))))

(defn generate-json
  [{:keys [resolved-deps mvn-repos]}]
  (json/write-str
    {:repos mvn-repos
     :packages (reduce generate-item {} (seq resolved-deps))}
    :indent true
    :escape-slash false))

(defn- usage []
  (->> ["clj2nix"
        ""
        "Usage: clj2nix deps.edn deps-lock.json [options]"
        ""
        "Options:"
        "  -Aalias    read deps from an alias and append it to the classpath"
        ""
        "Please report bugs to https://github.com/hlolli/clj2nix"]
       (string/join "\n")))

(def ^:private cli-options
  [["-A" "--alias ALIAS" "Alias name"
    :default []
    :parse-fn #(if (= \: (first %))
                 (read-string %)
                 (identity %))
    :assoc-fn (fn [m id value] (update m id conj value))
    :validate [#(or (keyword? %) (and (string? %) (< 0 (count %))))
               "An alias can't be empty value and must either be a keyword or symbol"]]
   ["-h" "--help"]])

(defn -main [deps-edn-path output-path & opts]
  (let [{:keys [options summary errors] :as parsed-args}
        (parse-opts (into [] (or opts [])) cli-options)
        deps-edn-data (edn/read-string (slurp deps-edn-path))
        mvn-repos (get deps-edn-data :mvn/repos mvn/standard-repos)
        aliases (->> (:alias options)
                     (map (fn [alias] (get-in deps-edn-data [:aliases alias])))
                     (map (fn [alias-data] (:extra-deps alias-data)))
                     (remove nil?)
                     (into []))
        deps-to-resolve (into (or (:deps deps-edn-data) {})
                              (reduce into [] aliases))
        deps-edn-data (assoc deps-edn-data
                             :deps deps-to-resolve
                             :mvn/repos mvn-repos)
        resolved-deps (deps/resolve-deps deps-edn-data nil)]
    (when (some #(not (contains? (:aliases deps-edn-data) %)) (:alias options))
      (println "Warning: non-existent alias was provided"
               (remove #(get (into #{} (keys (:aliases deps-edn-data))) %) (:alias options))))
    (cond
      errors (println "Error in clj2nix options:" errors)
      (:help options) (println (usage))
      :else (do
              (spit output-path
               (generate-json
                {:resolved-deps resolved-deps
                 :mvn-repos mvn-repos})))))
  (System/exit 0))
