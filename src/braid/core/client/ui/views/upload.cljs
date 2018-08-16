(ns braid.core.client.ui.views.upload
  (:require
   [braid.uploads.s3 :as s3]
   [re-frame.core :refer [dispatch]]
   [reagent.core :as r]))

(def max-avatar-size (* 2 1024 1024))

(defn avatar-upload-view [args]
  (let [uploading? (r/atom false)
        start-upload (fn [on-upload file-list]
                       (let [file (aget file-list 0)]
                         (if (> (.-size file) max-avatar-size)
                           (dispatch [:braid.notices/display! [:avatar-set-fail "Avatar image too large" :error]])
                           (do (reset! uploading? true)
                               (s3/upload
                                 file
                                 (fn [url]
                                   (reset! uploading? false)
                                   (on-upload url)))))))]
    (fn [{:keys [on-upload dragging-change] :as args}]
      [:div.upload
       (if @uploading?
         [:div
          [:p "Uploading..." [:span.uploading-indicator "\uf110"]]]
         [:div
          {:on-drag-over (fn [e]
                           (doto e (.stopPropagation) (.preventDefault))
                           (dragging-change true))
           :on-drag-leave (fn [_] (dragging-change false))
           :on-drop (fn [e]
                      (.preventDefault e)
                      (dragging-change false)
                      (reset! uploading? true)
                      (start-upload on-upload (.. e -dataTransfer -files)))}
          [:label "Choose an avatar image"
           [:input {:type "file" :accept "image/*"
                    :on-change (fn [e]
                                 (start-upload on-upload (.. e -target -files)))}]]])])))
