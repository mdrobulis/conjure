(ns conjure.config
  "Tools to load all relevant  .conjure.edn files.
  They're used to manage connection configuration."
  (:require [clojure.string :as str]
            [clojure.spec.alpha :as s]
            [clojure.tools.reader :as tr]
            [expound.alpha :as expound]
            [taoensso.timbre :as log]
            [me.raynes.fs :as fs]
            [traversy.lens :as tl]
            [conjure.ui :as ui]
            [conjure.util :as util]))

(s/def ::extensions (s/coll-of string? :kind set?))
(s/def ::dirs (s/coll-of string? :kind set?))
(s/def ::port (s/nilable number?))
(s/def ::lang #{:clj :cljs})
(s/def ::host string?)
(s/def ::tag keyword?)
(s/def ::enabled? boolean?)
(s/def ::exclude-path? any?)
(s/def ::hook #{:connect! :result! :refresh :eval})
(s/def ::hooks (s/map-of ::hook any?))
(s/def ::conn (s/keys :req-un [::port ::host ::lang ::extensions ::enabled?]
                      :opt-un [::hooks ::dirs ::exclude-path?]))
(s/def ::conns (s/map-of ::tag ::conn))
(s/def ::config (s/nilable (s/keys :opt-un [::conns ::hooks])))

(def ^:private default-extensions
  {:clj #{"clj" "cljc" "edn"}
   :cljs #{"cljs" "cljc" "edn"}})

(defn- parse
  "Parse the given string as data with tools.reader.
  Allows for slurping further files."
  [s cwd]
  (let [readers {'slurp-edn
                 (fn [path]
                   (try
                     (-> (fs/file cwd path)
                         (slurp)
                         (parse cwd))
                     (catch Throwable t
                       (log/error "Caught error while slurping EDN" t))))}]
    (binding [tr/*data-readers* (merge tr/default-data-readers readers)
              *read-eval* false]
      (tr/read-string {:read-cond :preserve} s))))

(defn- ^:dynamic gather!
  "Gather all config files from disk and merge them together, deepest file wins."
  [{:keys [cwd] :as _opts}]
  (->> (concat [(fs/file (or (util/env :xdg-config-home)
                             (fs/file (fs/home) ".config"))
                         "conjure")]
               (fs/parents cwd)
               [(fs/file cwd)])
       (reverse)
       (transduce
         (comp (mapcat (fn [dir] [(fs/file dir "conjure.edn")
                                  (fs/file dir ".conjure.edn")]))
               (filter (every-pred fs/file? fs/readable?))
               (map slurp)
               (map #(parse % cwd)))
         util/deep-merge)))

(defn hydrate-conn
  "Infer some values of a specific connection."
  [conn]
  (merge {:lang :clj
          :extensions (get default-extensions (get conn :lang :clj))
          :host "127.0.0.1"
          :enabled? true}
         conn))

(defn- hydrate
  "Infer some more values from the existing config."
  [config]
  (-> config
      (tl/update (tl/*> (tl/in [:conns]) tl/all-values) hydrate-conn)))

(defn- validate
  "Ensure the config conforms to the ::config spec, throws."
  [config]
  (if (s/valid? ::config config)
    config
    (ui/error (str "Something's wrong with your .conjure.edn!\n"
                   (expound/expound-str ::config config)))))

(defn toggle [config flags]
  (if-let [flags (and (not (str/blank? flags))
                      (not-empty (str/split flags #"\s+")))]
    (transduce
      (comp
        (map (fn [flag]
               {:tag (keyword (util/safe-subs flag 1))
                :enabled? (case (first flag)
                            \- false
                            \+ true
                            nil) }))
        (remove (comp nil? :enabled?)))
      (completing
        (fn [config {:keys [tag enabled?]}]
          (tl/update config (tl/in [:conns tag])
                     (fn [conn]
                       (assoc conn :enabled? enabled?)))))
      config
      flags)
    config))

(defn fetch
  "Gather, hydrate and validate the config."
  ([] (fetch {}))
  ([{:keys [flags cwd] :or {cwd "."} :as _opts}]
   (-> (gather! {:cwd cwd})
       (hydrate)
       (toggle flags)
       (validate))))

(defn hook
  "Given config and a potential conn tag, fetch the given hook data.
  The conn specific config will override the general one."
  [{:keys [config tag hook]}]
  (or (when tag
        (get-in config [:conns tag :hooks hook]))
      (get-in config [:hooks hook])))
