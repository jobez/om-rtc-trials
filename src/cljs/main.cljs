(ns rtc.main
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  ( :require [cljs.reader :refer [read-string]]
             [goog.events :as events]
             [goog.dom :as gdom]
             [rtc.peer-connection :as pc]
             [om-websocket.core :refer [om-websocket] :as ws]
             [om-tools.core :refer-macros [defcomponent]]
             [om-tools.dom :as dom :include-macros true]
             [goog.debug :as debug]
             [clojure.string :as string]
             [cljs.core.async :as async :refer [chan put! pipe unique map< filter< alts! <!]]
             [om.core :as om :include-macros true]))

(enable-console-print!)

(def app-state (atom {}))

(defn handle-stream [owner stream node]
  (om/set-state! owner :stream stream)
  (case js/webrtcDetectedBrowser
    "firefox" (set! (.-mozSrcObject node) stream)
    (set! (.-src node)  (.createObjectURL js/URL stream))
    ) 
  (.play node))


(defn vid-loop [owner data vidc]
  (go-loop [[key ref stream] (<! vidc)]
           (let [webc (om/get-shared owner :websocket->)
                 pc (om/get-state owner :pc)
                 role (@data :role)
                 ]
             
             (case key
               :local (let [node (om/get-node owner ref)]
                        (.addStream pc stream)
                        (when (= role :joiner)
                          (put! webc [:stream-added]))
                       (handle-stream owner stream node))
               :remote (let [node (om/get-node owner ref)]
                         (print "HEY I AM BEING RAN")
                         (handle-stream owner stream node)
                         )
               (print "TROUBLE IN VID-LOOP")
               ) 
             )

           
           
           (recur (<! vidc))))

(def error #(print (.-name %)))

(defn locals [constraints owner c]
  (let [constraints (clj->js constraints)]
      (js/getUserMedia constraints #(put! c [:local "lvid"  %])  #(print (debug/expose %)))))


(defn dcsend [owner]
  (let [sc (om/get-state owner :sc)
        node (om/get-node owner "dc")
        val (.-value node)
        ]
    (.send sc val)
    (set! (.-value node) "")))


(defcomponent final-rtc [data owner opts]
  (init-state [_]
              (let [
                    pc-config (if (= "firefox" js/webrtcDetectedBrowser)
                                #js {:iceServers #js [ #js {:url "stun:23.21.150.121"}]}
                                #js {:iceServers #js [ #js {:url "stun:stun.l.google.com:19302"}]})
                    pc-constants #js {:optional #js [ #js {:DtlsSrtpKeyAgreement true}]} 
                    pc (js/RTCPeerConnection pc-config pc-constants)
                    ]
                {:pc pc
                 :msgs []
                 :streamc (chan)}))
  
  (will-mount [_]
              (let [streamc (om/get-state owner :streamc)
                    {:keys [constraints] :as settings} (om/get-state owner :settings)]
                (pc/pc owner data settings)
                (when constraints (locals constraints owner streamc))
                (vid-loop owner data streamc)
                ))
  (render-state [_ {:keys [sc msgs settings]}]
                (let [webc (om/get-shared owner :websocket->)]
                  (dom/div {:class "full flex"}
                           (when sc
                             (dom/div {:class "column flex"}
                                      (dom/input {:type "text" :ref "dc"})
                                      (dom/button {:on-click #(dcsend owner)} "send SECRET")
                                      (dom/div
                                       (map (fn [msg]
                                              (dom/div msg)
                                              ) msgs))))
                           
                           (when (settings :constraints)
                             (dom/div
                              (dom/video {:ref "lvid"})
                              (dom/video {:ref "rvid"})))))
                ))



(defcomponent room-space [{:keys [role r-stream-added] :as data} owner opts]
  (render-state [_ {:keys [constraints]}]
                (let [webc (om/get-shared owner :websocket->)]
                  (dom/div {:class "full flex"}
                           (when (= role :joiner)
                             (dom/button {:on-click #(put! webc [:connect])} "start" ))
                           (when (data :role) 
                             (om/build final-rtc data {:state {:settings {:data-channel false
                                                                          :constraints {:video true}}}} ))
                           ))
                ))




(defn in-coord [chan data owner]
  (go-loop [signal (<! chan)]
           (let [pc-coord (om/get-shared owner :pc-coord)
                 sig (read-string signal)
                 key (get sig 0)
                 val (get sig 1)]
             (print sig)
             (case key
               :created (om/update! data :role :initiator)
               :joined (om/update! data :role :joiner)
               :offer (put! pc-coord [key])
               :r-candidate (put! pc-coord  [key val])
               :r-stream-added (om/update! data :r-stream-added true)
               :r-offer (put! pc-coord [key val])
               (print "key :" key "val :" val "w"))
             (recur (<! chan)))))


(defn out-coord [chan data owner websock]
  (let [sockout #(.send websock [% %2])]
    (go-loop [[key val :as sig] (<! chan)]
             
               (case key
                 
                 :connect  (sockout key "")
                 :stream-added (sockout key "")
                 
                 :s-candidate  (sockout key val) 
                 :s-offer (sockout key val)  
                 (print "whatever"))
               (recur (<! chan)))))


(defcomponent app-view [data owner]
  (render-state [_ _]
                (om/build om-websocket data {:opts {:connection-str (str "ws://" js/location.host "/ws")
                                                    :in-coord in-coord
                                                    :out-coord out-coord
                                                    :child room-space}})
                ))

(om/root app-view
         app-state
         {:target (gdom/getElement "app")
          :tx-listen #(print %)
          :shared {:vidc (chan)
                   :pc-coord (chan)
                   :websocket-> (chan)}})
