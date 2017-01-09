(ns clojars.db
  (:require [clojure.string :as str]
            [clj-time.core :as time]
            [clj-time.coerce :as time.coerce]
            [clojars.config :refer [config]]
            [clojars.db.sql :as sql]
            [clojars.maven :as mvn]
            [cemerick.friend.credentials :as creds]
            [clojure.edn :as edn]
            [clojure.set :as set])
  (:import java.util.Date
           java.security.SecureRandom
           java.util.concurrent.Executors))

(def reserved-names
  #{"clojure" "clojars" "clojar" "register" "login"
    "pages" "logout" "password" "username" "user"
    "repo" "repos" "jar" "jars" "about" "help" "doc"
    "docs" "images" "js" "css" "maven" "api"
    "download" "create" "new" "upload" "contact" "terms"
    "group" "groups" "browse" "status" "blog" "search"
    "email" "welcome" "devel" "development" "test" "testing"
    "prod" "production" "admin" "administrator" "root"
    "webmaster" "profile" "dashboard" "settings" "options"
    "index" "files" "releases" "snapshots"})

(defn get-time []
  (Date.))

(defn bcrypt [s]
  (creds/hash-bcrypt s :work-factor (:bcrypt-work-factor @config)))

(defn find-user [db username]
  (sql/find-user {:username username}
                 {:connection db
                  :result-set-fn first}))

(defn find-user-by-user-or-email [db username-or-email]
  (sql/find-user-by-user-or-email {:username_or_email username-or-email}
                                  {:connection db
                                   :result-set-fn first}))

(defn find-user-by-password-reset-code [db reset-code]
  (sql/find-user-by-password-reset-code {:reset_code reset-code
                                         :reset_code_created_at
                                         (-> 1 time/days time/ago time.coerce/to-long)}
                                        {:connection db
                                         :result-set-fn first}))

(defn find-groupnames [db username]
  (sql/find-groupnames {:username username}
                       {:connection db
                        :row-fn :name}))

(defn group-membernames [db groupname]
  (sql/group-membernames {:groupname groupname}
                         {:connection db
                          :row-fn :user}))

(defn jars-by-username [db username]
  (sql/jars-by-username {:username username}
                        {:connection db}))

(defn jars-by-groupname [db groupname]
  (sql/jars-by-groupname {:groupname groupname}
                         {:connection db}))

(defn recent-versions
  ([db groupname jarname]
   (sql/recent-versions {:groupname groupname
                         :jarname jarname}
                        {:connection db}))
  ([db groupname jarname num]
   (sql/recent-versions-limit {:groupname groupname
                               :jarname jarname
                               :num num}
                              {:connection db})))

(defn count-versions [db groupname jarname]
  (sql/count-versions {:groupname groupname
                       :jarname jarname}
                      {:connection db
                       :result-set-fn first
                       :row-fn :count}))

(defn recent-jars [db]
  (sql/recent-jars {} {:connection db}))

(defn jar-exists [db groupname jarname]
  (sql/jar-exists {:groupname groupname
                   :jarname jarname}
                  {:connection db
                   :result-set-fn first
                   :row-fn #(= (:exist %) 1)}))

(let [read-field (fn [m field] (update m field (fnil edn/read-string "nil")))
      read-edn-fields #(when %
                        (-> %
                            (read-field :licenses)
                            (read-field :scm)))]
  (defn find-jar
    ([db groupname jarname]
     (read-edn-fields
       (sql/find-jar {:groupname groupname
                      :jarname   jarname}
                     {:connection    db
                      :result-set-fn first})))
    ([db groupname jarname version]
     (read-edn-fields
       (sql/find-jar-versioned {:groupname groupname
                                :jarname   jarname
                                :version   version}
                               {:connection    db
                                :result-set-fn first}))))
  (defn all-jars [db]
    (map read-edn-fields
         (sql/all-jars {} {:connection db}))))

(defn find-dependencies
  [db groupname jarname version]
  (sql/find-dependencies {:groupname groupname
                          :jarname   jarname
                          :version   version}
                         {:connection db}))

(defn all-projects [db offset-num limit-num]
  (sql/all-projects {:num limit-num
                     :offset offset-num}
                    {:connection db}))

(defn count-all-projects [db]
  (sql/count-all-projects {}
                          {:connection db
                           :result-set-fn first
                           :row-fn :count}))

(defn count-projects-before [db s]
  (sql/count-projects-before {:s s}
                             {:connection db
                              :result-set-fn first
                              :row-fn :count}))

(defn browse-projects [db current-page per-page]
  (vec
   (map
    #(find-jar db (:group_name %) (:jar_name %))
    (all-projects db
     (* (dec current-page) per-page)
     per-page))))

(def write-executor (memoize #(Executors/newSingleThreadExecutor)))

(def ^:private ^:dynamic *in-executor* nil)

(defn serialize-task* [task-name task]
  (if *in-executor*
    (task)
    (binding [*in-executor* true]
      (let [bound-f (bound-fn []
                      (try
                        (task)
                        (catch Throwable e
                          e)))
            response (deref
                      (.submit (write-executor) bound-f)
                      10000 ::timeout)]
        (cond
          (= response ::timeout) (throw
                                  (ex-info
                                   "Timed out waiting for serialized task to run"
                                   {:name task-name}))
          (instance? Throwable response) (throw
                                          (ex-info "Serialized task failed"
                                                   {:name task-name}
                                                   response))
          :default response)))))

(defmacro serialize-task [name & body]
  `(serialize-task* ~name
                    (fn [] ~@body)))

(defn add-user [db email username password]
  (let [record {:email email, :username username, :password (bcrypt password),
                :created (get-time)}
        groupname (str "org.clojars." username)]
    (serialize-task :add-user
                    (sql/insert-user! record
                                      {:connection db})
                    (sql/insert-group! {:groupname groupname :username username}
                                       {:connection db}))
    record))

(defn update-user [db account email username password]
  (let [fields {:email email
                :username username
                :account account}]
    (serialize-task :update-user
                    (if (empty? password)
                      (sql/update-user! fields {:connection db})
                      (sql/update-user-with-password!
                        (assoc fields :password
                                      (bcrypt password))
                        {:connection db})))
    fields))

(defn reset-user-password [db username reset-code password]
  (assert (not (str/blank? reset-code)))
  (assert (some? username))
  (serialize-task :reset-user-password
                    (sql/reset-user-password! {:password (bcrypt password)
                                               :reset_code reset-code
                                               :username username}
                                               {:connection db})))

  ;; Password resets
  ;; Reference:
  ;; https://github.com/xavi/noir-auth-app/blob/master/src/noir_auth_app/models/user.clj
  ;; https://github.com/weavejester/crypto-random/blob/master/src/crypto/random.clj
  ;; https://jira.atlassian.com/browse/CWD-1897?focusedCommentId=196759&page=com.atlassian.jira.plugin.system.issuetabpanels:comment-tabpanel#comment-196759
(defn generate-secure-token [size]
  (let [seed (byte-array size)]
    ;; http://docs.oracle.com/javase/6/docs/api/java/security/SecureRandom.html
    (.nextBytes (SecureRandom/getInstance "SHA1PRNG") seed)
    seed))

(defn hexadecimalize [byte-array]
                                        ; converts byte array to hex string
                                        ; http://stackoverflow.com/a/8015558/974795
  (str/lower-case (apply str (map #(format "%02X" %) byte-array))))

(defn set-password-reset-code! [db username]
  (let [reset-code (hexadecimalize (generate-secure-token 20))]
    (serialize-task :set-password-reset-code
                    (sql/set-password-reset-code! {:reset_code reset-code
                                                   :reset_code_created_at (get-time)
                                                   :username username}
                                                  {:connection db}))
    reset-code))

(defn add-member [db groupname username added-by]
  (if (serialize-task :group-member
                      (sql/group-member {:groupname groupname
                                         :username username}
                                        {:connection db}))
    (serialize-task :undelete-member
                    (sql/undelete-member! {:groupname groupname
                                           :username username}
                                          {:connection db}))
    (serialize-task :add-member
                    (sql/add-member! {:groupname groupname
                                      :username username
                                      :added_by added-by}
                                     {:connection db}))))

(defn check-group
  "Throws if the group is invalid or not accessible to the account"
  [members account groupname]
  (let [err (fn [msg]
              (throw (ex-info msg {:account account
                                   :group groupname})))]
    (when (reserved-names groupname)
      (err (format "The group name '%s' is reserved" groupname)))
    (when (and (seq members)
            (not (some #{account} members)))
      (err (format "You don't have access to the '%s' group" groupname)))))

(defn check-and-add-group [db account groupname]
  (let [members (group-membernames db groupname)]
    (check-group members account groupname)
    (when (empty? members)
      (add-member db groupname account "clojars"))))

(defn add-jar [db account {:keys [group name version description homepage authors packaging licenses scm dependencies]}]
  (check-and-add-group db account group)
  (serialize-task :add-jar
                  (sql/add-jar! {:groupname   group
                                 :jarname     name
                                 :version     version
                                 :user        account
                                 :created     (get-time)
                                 :description description
                                 :homepage    homepage
                                 :packaging   (when packaging (clojure.core/name packaging))
                                 :licenses    (when licenses (pr-str licenses))
                                 :scm         (when scm (pr-str scm))
                                 :authors     (str/join ", " (map #(.replace % "," "")
                                                                  authors))}
                                {:connection db})
                  (when (mvn/snapshot-version? version)
                    (sql/delete-dependencies-version!
                      {:group_id group
                       :jar_id name
                       :version version}
                      {:connection db}))
                  (doseq [dep dependencies]
                    (sql/add-dependency! (-> dep
                                             (set/rename-keys {:group_name :dep_groupname
                                                               :jar_name   :dep_jarname
                                                               :version    :dep_version
                                                               :scope      :dep_scope})
                                             (assoc :groupname group
                                                    :jarname   name
                                                    :version   version))
                                         {:connection db}))))

(defn delete-jars [db group-id & [jar-id version]]
  (serialize-task :delete-jars
                  (let [coords {:group_id group-id}]
                    (if jar-id
                      (let [coords (assoc coords :jar_id jar-id)]
                        (if version
                          (let [coords' (assoc coords :version version)]
                            (sql/delete-jar-version! coords'
                                                     {:connection db})
                            (sql/delete-dependencies-version! coords'
                                                              {:connection db}))
                          (do
                            (sql/delete-jars! coords
                                              {:connection db})
                            (sql/delete-dependencies! coords
                                                      {:connection db}))))
                      (do
                        (sql/delete-groups-jars! coords
                                                 {:connection db})
                        (sql/delete-groups-dependencies! coords
                                                         {:connection db}))))))

;; does not delete jars in the group. should it?
(defn delete-groups [db group-id]
  (serialize-task :delete-groups
                  (sql/delete-group! {:group_id group-id}
                                     {:connection db})))

(defn find-jars-information
  ([db group-id]
   (find-jars-information db group-id nil))
  ([db group-id artifact-id]
   (if artifact-id
     (sql/find-jars-information {:group_id group-id
                                 :artifact_id artifact-id}
                                {:connection db})
     (sql/find-groups-jars-information {:group_id group-id}
                                       {:connection db}))))

