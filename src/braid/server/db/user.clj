(ns braid.server.db.user
  (:require [datomic.api :as d]
            [clojure.edn :as edn]
            [clojure.string :as string]
            [crypto.password.scrypt :as password]
            [braid.server.db :as db]
            [braid.server.db.common :refer :all]
            [braid.server.quests.db :refer [activate-first-quests-txn]]))

;; Queries

(defn email-taken?
  [email]
  (some? (d/entity (db/db) [:user/email email])))

(defn nickname-taken?
  [nickname]
  (some? (d/entity (db/db) [:user/nickname nickname])))

(defn authenticate-user
  "returns user-id if email and password are correct"
  [email password]
  (->> (let [[user-id password-token]
             (d/q '[:find [?id ?password-token]
                    :in $ ?email
                    :where
                    [?e :user/id ?id]
                    [?e :user/email ?stored-email]
                    [(.toLowerCase ^String ?stored-email) ?email]
                    [?e :user/password-token ?password-token]]
                  (db/db)
                  (.toLowerCase email))]
         (when (and user-id (password/check password password-token))
           user-id))))

(defn user-by-id
  [id]
  (some-> (d/pull (db/db) user-pull-pattern [:user/id id])
          db->user))

(defn user-id-exists?
  [id]
  (some? (d/entity (db/db) [:user/id id])))

(defn user-with-email
  "get the user with the given email address or nil if no such user registered"
  [email]
  (some-> (d/pull (db/db) user-pull-pattern [:user/email email])
          db->user))

(defn user-email
  [user-id]
  (:user/email (d/pull (db/db) [:user/email] [:user/id user-id])))

(defn user-get-preferences
  [user-id]
  (->> (d/pull (db/db)
               [{:user/preferences [:user.preference/key :user.preference/value]}]
               [:user/id user-id])
       :user/preferences
       (into {}
             (comp (map (juxt :user.preference/key :user.preference/value))
                   (map (fn [[k v]] [k (edn/read-string v)]))))))

(defn user-get-preference
  [user-id pref]
  (some-> (d/q '[:find ?val .
                 :in $ ?user-id ?key
                 :where
                 [?u :user/id ?user-id]
                 [?u :user/preferences ?p]
                 [?p :user.preference/key ?key]
                 [?p :user.preference/value ?val]]
               (db/db) user-id pref)
          edn/read-string))

(defn user-preference-is-set?
  "If the preference with the given key has been set for the user, return the
  entity id, else nil"
  [user-id pref]
  (d/q '[:find ?p .
         :in $ ?user-id ?key
         :where
         [?u :user/id ?user-id]
         [?u :user/preferences ?p]
         [?p :user.preference/key ?key]]
       (db/db) user-id pref))

(defn user-search-preferences
  "Find the ids of users that have the a given value for a given key set in
  their preferences"
  [k v]
  (d/q '[:find [?user-id ...]
         :in $ ?k ?v
         :where
         [?u :user/id ?user-id]
         [?u :user/preferences ?pref]
         [?pref :user.preference/key ?k]
         [?pref :user.preference/value ?v]]
       (db/db)
       k (pr-str v)))

(defn users-for-user
  "Get all users visible to given user"
  [user-id]
  (->> (d/q '[:find (pull ?e pull-pattern)
              :in $ ?user-id pull-pattern
              :where
              [?u :user/id ?user-id]
              [?g :group/user ?u]
              [?g :group/user ?e]]
            (db/db)
            user-id
            user-pull-pattern)
       (map (comp db->user first))
       set))

(defn user-visible-to-user?
  "Are the two user ids users that can see each other? i.e. do they have at least one group in common"
  [user1-id user2-id]
  (-> (d/q '[:find ?g
             :in $ ?u1-id ?u2-id
             :where
             [?u1 :user/id ?u1-id]
             [?u2 :user/id ?u2-id]
             [?g :group/user ?u1]
             [?g :group/user ?u2]]
           (db/db) user1-id user2-id)
      seq boolean))

;; Transactions

(defn create-user-txn
  "creates a user, returns the newly-created user"
  [{:keys [id email avatar nickname password]}]
  (let [new-id (d/tempid :entities)]
    (into
      [^{:return (fn [{:keys [db-after tempids]}]
                   (->> (d/resolve-tempid db-after tempids new-id)
                        (d/entity db-after)
                        db->user))}
       {:db/id new-id
        :user/id id
        :user/email email
        :user/avatar avatar
        :user/nickname (or nickname (-> email (string/split #"@") first))
        :user/password-token (password/encrypt password)}]
      (activate-first-quests-txn new-id))))

(defn set-nickname-txn
  "Set the user's nickname"
  [user-id nickname]
  [[:db/add [:user/id user-id] :user/nickname nickname]])

(defn set-user-avatar-txn
  [user-id avatar]
  [[:db/add [:user/id user-id] :user/avatar avatar]])

(defn set-user-password-txn
  [user-id password]
  [[:db/add [:user/id user-id] :user/password-token (password/encrypt password)]])

(defn user-set-preference-txn
  "Set a key to a value for the user's preferences."
  [user-id k v]
  (if-let [e (user-preference-is-set? user-id k)]
    [[:db/add e :user.preference/value (pr-str v)]]
    [{:user.preference/key k
      :user.preference/value (pr-str v)
      :user/_preferences [:user/id user-id]
      :db/id (d/tempid :entities)}]))
