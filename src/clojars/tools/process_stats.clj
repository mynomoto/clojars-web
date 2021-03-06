(ns clojars.tools.process-stats
  "generate usage statistics from web log"
  (:require [clojars.file-utils :as fu]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [net.cgrand.regex :as re]
            [clj-time.format :as timef])
  (:import java.util.regex.Pattern
           java.io.BufferedReader)
  (:gen-class))

(def time-clf (timef/formatter "dd/MMM/YYYY:HH:mm:ss Z"))
(def time-cdn (timef/formatters :date-time-no-ms))

;; net.cgrand/regex currently doesn't allow Patterns
;; but they're too handy so let's enable them anyway
(extend-type Pattern
  re/RegexValue
  (pattern [re] (.pattern re))
  (groupnames [_] [])
  re/RegexFragment
  (static? [_ _] true))

(def re-clf ; common log format (apache, nginx etc)
  (let [field #"\S+"
        nonbracket #"[^\]]+"
        nonquote #"[^\" ]+"
        reqline (list [nonquote :as :method] \space
                      [nonquote :as :path] \space
                      [nonquote :as :protocol])]
    (re/regex [field :as :host] \space
              [field :as :ident] \space
              [field :as :authuser] \space
              \[ [nonbracket :as :time] \] #"\s+"
              \" reqline \" \space
              [field :as :status] \space
              [field :as :size]
              #".*")))

(def re-cdn ; log format from our fastly cdn
  (let [field #"\S+"
        nonquote #"[^\" ]+"
        reqline (list [nonquote :as :method] \space
                      [nonquote :as :path])]
    (re/regex \< #"\d+" \>
      [field :as :time] \space
      [field :as :cache-host] \space
      [field :as :endpoint] \: \space
      [field :as :host] \space
      \" [field :as :ident] \" \space
      \" reqline \" \space
      [field :as :status] \space
      [field :as :size]
      #".*")))

(def re-path
  (let [segment #"[^/]+"
        sep #"/+"]
    (re/regex (re/? sep "repo") sep
              [(re/* segment sep) segment :as :group] sep
              [segment :as :name] sep
              [segment :as :version] sep
              segment \.
              [#"\w+" :as :ext])))

(defn is-cdn? [line]
  (.startsWith line "<"))

(defn parse-path [s]
  (when s
    (when-let [m (re/exec re-path s)]
      {:name (:name m)
       :group (fu/path->group (:group m))
       :version (:version m)
       :ext (:ext m)})))

(defn parse-long [s]
  (when-not (#{nil "" "-"} s)
    (try (Long/parseLong s)
         (catch NumberFormatException e))))

(defn parse-line [line]
  (let [cdn? (is-cdn? line)
        m (re/exec (if cdn? re-cdn re-clf) line)]
    (merge
     (parse-path (:path m))
     {:status (parse-long (:status m))
      :method (:method m)
      :size (parse-long (:size m))
      :time (when (:time m) (try (timef/parse (if cdn? time-cdn time-clf) (:time m))
                                 (catch IllegalArgumentException e)))})))

(defn valid-download? [m]
  (and m
       (= (:status m) 200)
       (= (:method m) "GET")
       (= (:ext m) "jar")))

(def as-year-month (partial timef/unparse (timef/formatters :year-month)))

(defn compute-stats [lines]
  (->> lines
       (map parse-line)
       (filter valid-download?)
       (map (juxt :group :name :version))
       (frequencies)
       (reduce-kv (fn [acc [a g v] n] (assoc-in acc [[a g] v] n)) {})))

(defn process-log [logfile]
  (with-open [rdr (io/reader logfile)]
    (compute-stats (line-seq rdr))))

(defn -main [& _]
  (-> *in*
      BufferedReader.
      line-seq
      compute-stats
      prn))
