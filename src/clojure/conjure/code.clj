(ns conjure.code
  "Tools to render code for evaluation. The response from these functions
  should be sent to an environment for evaluation."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.edn :as edn]
            [backtick :as bt]
            [conjure.util :as util]))

(def ^:private required-deps!
  "Files to load, in order, to add runtime dependencies to a REPL."
  (delay
    (let [required-deps
          (-> (str "conjure_deps/injection_order.edn")
              (io/resource)
              (slurp)
              (edn/read-string))]
      (run! require (map util/path->ns (:clj required-deps)))
      required-deps)))

(defmulti render
  "Render the template strings with opts."
  (fn [name _opts]
    name))

(defmacro ^:private deftemplate
  "Small helper to define templates with.
  See conjure.code/render multimethod."
  [name params & body]
  `(defmethod render ~name [_name# opts#]
     (let [~params [opts#]]
       ~@body)))

(def ^:private ^:dynamic *tmpl-pprint?* true)

(defmacro ^:private tmpl
  "Render code templates like syntax quoting in macros.
  Returns a pprinted string, sub-calls to tmpl will return data for composition."
  [& exprs]
  `(cond-> (binding [*tmpl-pprint?* false]
             (bt/template
               ~(if (= (count exprs) 1)
                  (first exprs)
                  `(do ~@exprs))))
     *tmpl-pprint?* (util/pprint-data)))

(defn- wrap-clojure-eval
  "Ensure the code is evaluated with reader conditionals and an optional
  line number of file path."
  [{:keys [path code line]}]
  (let [path-args (when-not (str/blank? path)
                    [path (last (str/split path #"/"))])]
    (tmpl
      (let [rdr (-> (java.io.StringReader. ~code)
                    (clojure.lang.LineNumberingPushbackReader.)
                    (doto (.setLineNumber ~(or line 1))))]
        (binding [*default-data-reader-fn* tagged-literal]
          (let [res (. clojure.lang.Compiler (load rdr ~@path-args))]
            (cond-> res (seq? res) (doall))))))))

;; Used in templates because my linter freaks out otherwise.
(def ^:private ns-sym 'ns)
(def ^:private require-sym 'require)

(deftemplate :hook [{:keys [value hook]}]
  (tmpl (~hook (quote ~value))))

(deftemplate :hook-str [{:keys [value hook]}]
  (str "(" hook "\n " value "\n)"))

(deftemplate :loaded-deps [{:keys [lang]}]
  (tmpl
    (->> '~(->> (get @required-deps! lang)
                (map util/path->ns))
         (filter find-ns)
         (set))))

(deftemplate :inject-deps [{:keys [lang loaded-deps]}]
  (let [new-deps (remove
                   (fn [dep]
                     (contains? loaded-deps (util/path->ns dep)))
                   (get @required-deps! lang))]
    (case lang
      :clj 
      (concat
        [(tmpl
           (~require-sym 'clojure.repl
                         'clojure.string
                         'clojure.java.io
                         'clojure.test))]
        (map #(wrap-clojure-eval
                {:code (-> (io/resource %)
                           (slurp))
                 :path %})
             new-deps))
      :cljs
      [(tmpl
         (~require-sym 'cljs.repl
                       'cljs.test
                       'clojure.string))])))

(deftemplate :eval [{:keys [conn code line]
                     {:keys [ns path]} :ctx}]
  (case (:lang conn)
    :clj
    (tmpl
      (~ns-sym ~(or ns 'user))
      ~(wrap-clojure-eval {:code code
                           :path path
                           :line line}))
    :cljs
    ;; Can't use tmpl completely since it's a code string, not data.
    ;; We could parse it but I'd like to avoid that, it can break.
    ;; If we start using self hosted ClojureScript tmpl will be fine.
    (let [wrap-forms? (-> (str "[\n" code "\n]")
                          (util/parse-code)
                          (count)
                          (not= 1))]
      (str
        (tmpl (in-ns '~(or ns 'cljs.user))) "\n"
        (when wrap-forms? "(do ")
        code "\n"
        (when wrap-forms? ")\n")))))

(deftemplate :load-file [{:keys [path]}]
  (tmpl (load-file ~path)))

(deftemplate :completions [{:keys [ns conn prefix context]}]
  (case (:lang conn)
    :clj
    (tmpl
      (conjure-deps.compliment.v0v3v9.compliment.core/completions
        ~prefix
        (merge
          {:ns (find-ns '~(or ns 'user))
           :extra-metadata #{:doc :arglists}}
          (when-let [context ~context]
            {:context context}))))

    ;; ClojureScript isn't supported by Compliment yet.
    ;; https://github.com/alexander-yakushev/compliment/pull/62
    :cljs (tmpl [])))

(deftemplate :doc [{:keys [conn name]}]
  (let [doc-symbol (case (:lang conn)
                     :clj 'clojure.repl/doc
                     :cljs 'cljs.repl/doc)]
    (tmpl
      (with-out-str
        (~doc-symbol ~(symbol name))))))

(deftemplate :source [{:keys [conn name]}]
  (let [source-symbol (case (:lang conn)
                        :clj 'clojure.repl/source
                        :cljs 'cljs.repl/source)]
    (tmpl
      (with-out-str
        (~source-symbol ~(symbol name))))))

(deftemplate :definition [{:keys [name conn]}]
  (let [name-sym (symbol name)]
    (tmpl
      (when-let [loc (if-let [sym (and (not (find-ns '~name-sym)) (resolve '~name-sym))]
                       (mapv (meta sym) [:file :line :column])
                       (when-let [syms (some-> '~name-sym
                                               ~(case (:lang conn)
                                                  :cljs 'identity
                                                  :clj 'find-ns)
                                               (ns-interns))]
                         (when-let [file (some-> syms first val meta :file)]
                           [file 1 1])))]
        (when-not (or (clojure.string/blank? (first loc)) (= (first loc) "NO_SOURCE_PATH"))
          (-> loc
              (update
                0
                ~(case (:lang conn)
                   :cljs 'identity
                   :clj (tmpl
                          (fn [file]
                            (if (.exists (clojure.java.io/file file))
                              file
                              (-> (clojure.java.io/resource file)
                                  (str)
                                  (clojure.string/replace #"^file:" "")
                                  (clojure.string/replace #"^jar:file" "zipfile")
                                  (clojure.string/replace #"\.jar!/" ".jar::")))))))
              (update 2 dec)))))))

(deftemplate :run-tests [{:keys [targets conn]}]
  (case (:lang conn)
    :clj
    (tmpl
      (with-out-str
        (binding [clojure.test/*test-out* *out*]
          (apply clojure.test/run-tests (keep find-ns '~targets)))))
    :cljs
    (tmpl
      (with-out-str
        (cljs.test/run-tests ~@(map #(list 'quote %) targets))))))

(deftemplate :run-all-tests [{:keys [re conn]}]
  (let [args (when re
               [(re-pattern re)])]
    (case (:lang conn)
      :clj
      (tmpl
        (with-out-str
          (binding [clojure.test/*test-out* *out*]
            (clojure.test/run-all-tests ~@args))))

      :cljs
      (tmpl
        (with-out-str
          (cljs.test/run-all-tests ~@args))))))

(deftemplate :refresh [{:keys [conn op hook]}]
  (when (= (:lang conn) :clj)
    (let [repl-ns "conjure-deps.toolsnamespace.v0v3v1.clojure.tools.namespace.repl"
          op-str (case op
                   :clear "clear"
                   :changed "refresh"
                   :all "refresh-all")]
      (tmpl
        (let [{:keys [before after dirs]} ~(when hook
                                             (render :hook {:hook hook}))]
          (when before
            (require (symbol (namespace before)))
            ((resolve before)))
          (when dirs
            (apply ~(symbol repl-ns "set-refresh-dirs") dirs))
          (apply ~(symbol repl-ns op-str)
                 (when (and (not= ~op :clear) after)
                   [:after after])))))))
