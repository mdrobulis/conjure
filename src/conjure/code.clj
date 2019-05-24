(ns conjure.code
  "Tools to render or format Clojure code."
  (:require [clojure.string :as str]
            [clojure.edn :as edn]
            [taoensso.timbre :as log]
            [conjure.util :as util]))

(defn sample
  "Get a short one line sample snippet of some code."
  [code]
  (when code
    (let [sample-length 50
          flat (str/replace code #"\s+" " ")]
      (if (> (count flat) sample-length)
        (str (subs flat 0 sample-length) "…")
        flat))))

(defn parse-code [code]
  (if (string? code)
    (binding [*default-data-reader-fn* tagged-literal]
      (read-string {:read-cond :preserve} code))
    code))

(defn parse-ns [code]
  (try
    (let [form (parse-code code)]
      [:ok
       (when (and (seq? form) (= (first form) 'ns))
         (second (filter symbol? form)))])
    (catch Throwable e
      (log/error "Caught error while extracting ns" e)
      [:error e])))

(def injected-deps
  "Files to load, in order, to add runtime dependencies to a REPL."
  (delay (edn/read-string (slurp "target/mranderson/load-order.edn"))))

(defn prelude-str [{:keys [lang]}]
  (case lang
    :clj (str "
              (require 'clojure.repl
                       'clojure.string
                       'clojure.java.io
                       'clojure.test)
              "
              (->> (deref injected-deps)
                   (map slurp)
                   (str/join "\n")) 
              "
              :ready
              ")
    :cljs "
          (require 'cljs.repl 'cljs.test)
          :ready
          "))

(defn eval-str [{:keys [ns path]} {:keys [conn code line]}]
  (let [path-args-str (when-not (str/blank? path)
                        (str " \"" path "\" \"" (last (str/split path #"/")) "\""))]
    (case (:lang conn)
      :clj
      (str "
           (try
             (ns " (or ns "user") ")
             (let [rdr (-> (java.io.StringReader. \"(do " (util/escape-quotes code) "\n)\")
                           (clojure.lang.LineNumberingPushbackReader.)
                           (doto (.setLineNumber " (or line 1) ")))]
               (binding [*default-data-reader-fn* tagged-literal]
                 (let [res (. clojure.lang.Compiler (load rdr" path-args-str "))]
                   [:ok (cond-> res (seq? res) (doall))])))
             (catch Throwable e
               (let [emap (Throwable->map e)]
                 (binding [*out* *err*]
                   (println (-> emap clojure.main/ex-triage clojure.main/ex-str)))
                 [:error emap]))
             (finally
               (flush)))
           ")

      :cljs
      (str "
           (in-ns '" (or ns "cljs.user") ")
           (try
             [:ok (do " code "\n)]
             (catch :default e
               (let [emap (cljs.repl/Error->map e)]
                 (println (-> emap cljs.repl/ex-triage cljs.repl/ex-str))
                 [:error emap]))
             (finally
               (flush)))
           "))))

(defn doc-str [{:keys [conn name]}]
  (case (:lang conn)
    :clj (str "(with-out-str (clojure.repl/doc " name "))")
    :cljs (str "(with-out-str (cljs.repl/doc " name "))")))

(defn load-file-str [path]
  (str "(load-file \"" path "\")"))

(defn completions-str [{:keys [ns]} {:keys [conn prefix context]}]
  (case (:lang conn)
    :clj
    (str "
         (conjure.compliment.v0v3v8.compliment.core/completions
           \"" (util/escape-quotes prefix) "\"
           {:ns (find-ns '" (or ns "user") ")
            " (when context
                (str ":context \"" (util/escape-quotes context) "\""))
           "})
         ")

    ;; ClojureScript isn't supported by Compliment yet.
    ;; https://github.com/alexander-yakushev/compliment/pull/62
    :cljs "[]"))

(defn definition-str [{:keys [name conn]}]
  (str "
       (when-let [loc (if-let [sym (and (not (find-ns '"name")) (resolve '"name"))]
                        (mapv (meta sym) [:file :line :column])
                        (when-let [syms "(case (:lang conn)
                                           :cljs (str "(ns-interns '"name")")
                                           :clj (str "(some-> (find-ns '"name") ns-interns)"))"]
                          (when-let [file (:file (meta (some-> syms first val)))]
                            [file 1 1])))]
         (when-not (or (clojure.string/blank? (first loc)) (= (first loc) \"NO_SOURCE_PATH\"))
           (-> loc
               (update
                 0
                 "
                 (case (:lang conn)
                   :cljs "identity"
                   :clj "(fn [file]
                           (if (.exists (clojure.java.io/file file))
                             file
                             (-> (clojure.java.io/resource file)
                                 (str)
                                 (clojure.string/replace #\"^file:\" \"\")
                                 (clojure.string/replace #\"^jar:file\" \"zipfile\")
                                 (clojure.string/replace #\"\\.jar!/\" \".jar::\"))))")
                 ")
               (update 2 dec))))
       "))

(defn run-tests-str [{:keys [targets conn]}]
  (let [targets-str (->> targets
                         (map #(str "'" %))
                         (str/join " "))]
    (case (:lang conn)
      :clj
      (str "
           (with-out-str
             (binding [clojure.test/*test-out* *out*]
               (apply clojure.test/run-tests (keep find-ns #{" targets-str "}))))
           ")

      :cljs
      (str "
           (with-out-str
             (cljs.test/run-tests " targets-str "))
           "))))

(defn run-all-tests-str [{:keys [re conn]}]
  (let [re-str (when re
                 (str " #\"" (util/escape-quotes re) "\""))]

    (case (:lang conn)
      :clj
      (str "
           (with-out-str
             (binding [clojure.test/*test-out* *out*]
               (clojure.test/run-all-tests" re-str ")))
           ")

      :cljs
      (str "
           (with-out-str
             (cljs.test/run-all-tests" re-str "))
           "))))

(defn resolve-var-str [name]
  (str "(let [x (resolve '" name ")]
          (when (var? x)
            x))"))
