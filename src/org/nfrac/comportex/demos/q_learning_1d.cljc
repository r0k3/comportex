(ns org.nfrac.comportex.demos.q-learning-1d
  (:require [org.nfrac.comportex.core :as core]
            [org.nfrac.comportex.protocols :as p]
            [org.nfrac.comportex.cells :as cells]
            [org.nfrac.comportex.synapses :as syn]
            [org.nfrac.comportex.encoders :as enc]
            [org.nfrac.comportex.util :as util :refer [round abs]]
            #?(:clj [clojure.core.async :refer [<! >! go]]
               :cljs [cljs.core.async :refer [<! >!]]))
    #?(:cljs (:require-macros [cljs.core.async.macros :refer [go]])))

(def input-dim [400])
(def on-bits 40)
(def coord-radius 60) ;; so 60+1+60 = 121 candidates (and we choose 40)
(def surface-coord-scale 60) ;; so neighbouring positions (x +/- 1) share ~50% bits
(def surface [0 0.5 1 1.5 2 1.5 1 0.5
              0 1 2 3 4 5 4 3 2
              1 1 1 1 1 1
              1 2 3 4 5 6 7 8 6 4 2
              ])

(def initial-input-val
  {:x 5
   :y (surface 5)
   :dy 0
   :dx 0})

(def spec
  {:column-dimensions [1000]
   :depth 4
   :distal-punish? true
   :duty-cycle-period 300
   :boost-active-duty-ratio 0.01
   :ff-potential-radius 0.15
   :ff-init-frac 0.5})

(def action-spec
  {:column-dimensions [30]
   :activation-level 0.20
   :ff-potential-radius 1.0
   :ff-init-frac 0.5
   :ff-perm-inc 0.05
   :ff-perm-dec 0.05
   :ff-perm-connected 0.10
   :ff-perm-init-lo 0.35
   :ff-perm-init-hi 0.45
   ;; chosen for exploration - fresh connections fully boosted > 1.0:
   :max-boost 3.0
   :global-inhibition? true
   :boost-active-every 1
   :duty-cycle-period 150
   :boost-active-duty-ratio 0.05
   :depth 1
   :q-alpha 0.2
   :q-discount 0.8
   ;; do not want temporal pooling here - actions are not static
   :temporal-pooling-max-exc 0.0
   ;; disable learning
   :freeze? true})

(def action->movement
  {:left -1
   :right 1})

;; lookup on columns of :action region
(def column->signal
  (zipmap (range)
          (for [motion [:left :right]
                influence (repeat 15 1.0)]
            [motion influence])))

(defn select-action
  [htm]
  (let [alyr (get-in htm [:regions :action :layer-3])
        acols (p/active-columns alyr)
        signals (map column->signal acols)]
    (->> signals
         (reduce (fn [m [motion influence]]
                   (assoc! m motion (+ (get m motion 0) influence)))
                 (transient {}))
         (persistent!)
         (shuffle)
         (apply max-key val)
         (key))))

(defn active-synapses
  [sg target-id ff-bits]
  (filter (fn [[in-id p]]
            (ff-bits in-id))
          (p/in-synapses sg target-id)))

(defn active-synapse-perms
  [sg target-id ff-bits]
  (keep (fn [[in-id p]]
          (when (ff-bits in-id) p))
        (p/in-synapses sg target-id)))

(defn mean [xs] (/ (apply + xs) (count xs)))

(defn q-learn
  [htm prev-htm reward]
  (update-in htm [:regions :action :layer-3]
             (fn [lyr]
               (let [prev-lyr (get-in prev-htm [:regions :action :layer-3])
                     {:keys [ff-perm-init-lo q-alpha q-discount]} (p/params lyr)
                     ff-bits (or (:in-ff-bits (:state lyr)) #{})
                     acols (:active-cols (:state lyr))
                     prev-ff-bits (or (:in-ff-bits (:state prev-lyr)) #{})
                     prev-acols (:active-cols (:state prev-lyr))
                     psg (:proximal-sg lyr)
                     aperms (mapcat (fn [col]
                                      (active-synapse-perms psg [col 0 0] ff-bits))
                                    acols)
                     Qt-st+1 (if (seq aperms)
                               (- (mean aperms) ff-perm-init-lo) ;; TODO: include boost?
                               0)
                     Qt-st (:Q-val (:Q-info lyr) 0)
                     learn-value (+ reward (* q-discount Qt-st+1))
                     adjust (* q-alpha (- learn-value Qt-st))
                     op (if (pos? adjust) :reinforce :punish)
                     seg-updates (map (fn [col]
                                        (syn/seg-update [col 0 0] op nil nil))
                                      prev-acols)]
                 (->
                  (p/layer-learn lyr)
                  (assoc :proximal-sg
                         (p/bulk-learn psg seg-updates prev-ff-bits
                                       (abs adjust) (abs adjust) 0.0))
                  (assoc :Q-info {:Q-val Qt-st+1
                                  :Q-prev Qt-st
                                  :reward reward
                                  :lrn learn-value
                                  :adj adjust
                                  :perms (count aperms)}))))))

(defn make-model
  []
  (let [encoder (enc/pre-transform (fn [{:keys [x]}]
                                     {:coord [(* x surface-coord-scale)]
                                      :radii [coord-radius]})
                                   (enc/coordinate-encoder input-dim on-bits))
        mencoder (enc/pre-transform :dx (enc/linear-encoder 100 30 [-1 1]))
        sensory-input (core/sensorimotor-input encoder encoder)
        motor-input (core/sensorimotor-input nil mencoder)]
    (core/region-network {:rgn-1 [:input :motor]
                          :action [:rgn-1]}
                         {:input sensory-input
                          :motor motor-input}
                         core/sensory-region
                         {:rgn-1 (assoc spec :lateral-synapses? false)
                          :action action-spec})))

(defn feed-world-c-with-actions!
  [in-model-steps-c out-world-c model-atom]
  (go
   (loop [inval (assoc initial-input-val
                       :Q-map {})
          prev-htm @model-atom]
     (>! out-world-c inval)
     (when-let [htm (<! in-model-steps-c)]
       ;; scale reward to be comparable to [0-1] permanences
       (let [reward (* 0.5 (:dy inval))
             ;; do the Q learning on previous step
             upd-htm (swap! model-atom q-learn prev-htm reward)
             ;; maintain map of state+action -> approx Q values, for diagnostics
             info (get-in upd-htm [:regions :action :layer-3 :Q-info])
             newQ (-> (+ (:Q-prev info 0) (:adj info 0))
                      (max -1.0)
                      (min 1.0))
             Q-map (assoc (:Q-map inval)
                          (select-keys inval [:x :dx])
                          newQ)]
         (let [x (:x inval)
               act (select-action upd-htm)
               dx (action->movement act)
               next-x (-> (+ x dx)
                          (min (dec (count surface)))
                          (max 0))
               next-y (surface next-x)
               dy (- next-y (:y inval))]
           (recur {:x next-x
                   :y next-y
                   :dx dx
                   :dy dy
                   :Q-map Q-map}
                  upd-htm)))))))

(comment
  (require '[clojure.core.async :as async :refer [>!! <!!]])
  (require '[org.nfrac.comportex.protocols :as p])
  (def world-c (async/chan))
  (def model (atom (make-model)))
  (def steps-c (async/chan))

  (feed-world-c-with-actions! steps-c world-c model)

  (def inv (<!! world-c))
  (def model2 (swap! model p/htm-step inv))
  (>!! steps-c model2)

  inv
  (get-in @model [:regions :action :layer-3 :state :Q-val])
  (get-in @model [:regions :action :layer-3 :state :Q-info])
  (get-in @model [:regions :action :layer-3 :state :active-cols])

  )
