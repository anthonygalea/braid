(ns braid.server.db.bot
  (:require [datomic.api :as d]
            [braid.server.db :as db]
            [braid.server.db.common :refer [bot-pull-pattern db->bot]]
            [braid.server.db.user :as user]
            [braid.server.crypto :as crypto :refer [random-nonce]]))

;; Queries

(defn bots-in-group
  [group-id]
  (->> (d/q '[:find [(pull ?b pull-pattern) ...]
              :in $ pull-pattern ?group-id
              :where
              [?g :group/id ?group-id]
              [?b :bot/group ?g]]
            (db/db) bot-pull-pattern group-id)
       (into #{} (map db->bot))))

(defn bot-by-name-in-group
  [name group-id]
  (some-> (d/q '[:find (pull ?b pull-pattern) .
                 :in $ pull-pattern ?group-id ?name
                 :where
                 [?g :group/id ?group-id]
                 [?b :bot/group ?g]
                 [?b :bot/name ?name]]
               (db/db) bot-pull-pattern group-id name)
          db->bot))

(defn bot-auth?
  "Check if token is correct for the bot with the given id"
  [bot-id token]
  (some-> (d/pull (db/db) [:bot/token] [:bot/id bot-id])
          :bot/token
          (crypto/constant-comp token)))

(defn bot-by-id
  [bot-id]
  (db->bot (d/pull (db/db) bot-pull-pattern [:bot/id bot-id])))

(defn bots-watching-thread
  [thread-id]
  (some->> (d/q '[:find [(pull ?b pull-pattern) ...]
                  :in $ pull-pattern ?thread-id
                  :where
                  [?t :thread/id ?thread-id]
                  [?b :bot/watched ?t]]
                (db/db) bot-pull-pattern thread-id)
       (into #{} (map db->bot))))

;; Transactions

(defn create-bot-txn
  [{:keys [id name avatar webhook-url group-id]}]
  ; TODO: enforce name is unique in that group?
  (let [fake-user-id (d/tempid :entities)
        bot-id (d/tempid :entities)]
    [{:db/id fake-user-id
      :user/id (d/squuid)
      :user/is-bot? true}
     ^{:return
       (fn [{:keys [db-after tempids]}]
         (->> (d/resolve-tempid db-after tempids bot-id)
              (d/entity db-after)
              db->bot))}
     {:db/id bot-id
      :bot/id id
      :bot/name name
      :bot/avatar avatar
      :bot/webhook-url webhook-url
      :bot/token (random-nonce 30)
      :bot/group [:group/id group-id]
      :bot/user fake-user-id}]))

(defn bot-watch-thread-txn
  [bot-id thread-id]
  [^{:braid.server.db/check
     (fn [{:keys [db-after]}]
       (assert
         (= (get-in (d/entity db-after [:bot/id bot-id]) [:bot/group :group/id])
            (get-in (d/entity db-after [:thread/id thread-id]) [:thread/group :group/id]))
         (format "Bot %s tried to watch thread not in its group %s" bot-id thread-id)))}
   [:db/add [:bot/id bot-id] :bot/watched [:thread/id thread-id]]])
