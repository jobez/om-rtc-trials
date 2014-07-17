(ns rtc.messaging
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [cljs.reader :refer [read-string]]
            [goog.events :as events]
            [goog.dom :as gdom]
            goog.net.WebSocket
            [goog.debug :as debug]
            [om-tools.core :refer-macros [defcomponent]]
            [om-tools.dom :include-macros true]
            [clojure.string :as string]
            [cljs.core.async :as async :refer [chan put! pipe unique map< filter< alts! <!]]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true])
  (:import [goog.ui IdGenerator]))


(defn insock-loop [chan data owner]
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
               ;:answer (put! pc-coord [key])
               :r-candidate (put! pc-coord  [key val])
               :r-offer (put! pc-coord [key val])
               (print "key :" key "val :" val "w"))
             (recur (<! chan)))))

(defn outsock-loop [chan data websock owner]
  (let [sockout #(.send websock [% %2])
        client-id (om/get-state owner :client-id)]
    (go-loop [[key val :as sig] (<! chan)]
             
               (case key
                 ;:init (sockout key [client-id val])
                 :connect  (sockout key client-id)
                 ;:igum (sockout key client-id)
                 :s-candidate  (sockout key val) #_(sockout key (js->clj val))
                 :s-offer (sockout key val)  #_(sockout key (js->clj val))
                 (print "whatever"))
               (recur (<! chan)))))

(defcomponent websock [data owner opts]
  (init-state [_]
              {:websock (goog.net.WebSocket.)
               :in-c (chan)
              
               }
              )
  (will-mount [_]
              (let [webc (om/get-shared owner :webc)
                    in-c (om/get-state owner :in-c)
                    websock (om/get-state owner :websock)]
                (doto websock
                  (.addEventListener goog.net.WebSocket.EventType.CLOSED #(print (debug/expose %)))
                  (.addEventListener goog.net.WebSocket.EventType.OPENED  #(print "the channel is alive") )
                  (.addEventListener goog.net.WebSocket.EventType.MESSAGE #(put! in-c (.-message %)))
                  (.open  (str "ws://" js/location.host "/ws")))
                
                (insock-loop in-c data owner)
                (outsock-loop webc data websock owner)
                ))
  (render-state [_ _]
                (om/build (opts :child) data)) )



