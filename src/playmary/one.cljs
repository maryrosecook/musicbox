(ns playmary.one
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [goog.dom :as dom]
            [goog.events :as events]
            [cljs.core.async :as async :refer [<! put! chan timeout sliding-buffer close!]]
            [playmary.util :as util]
            [playmary.scales :as scales]))

(def timbre js/T)

(def colors [{:note "#676767" :light "#6DA0CB" :dark "#000000"}
             {:note "#929292" :light "#A76AB9" :dark "#1E1E1E"}
             {:note "#B9B9B9" :light "#BB67A2" :dark "#3D3D3D"}
             {:note "#DCDCDC" :light "#C55D83" :dark "#5C5C5C"}
             {:note "#FFFFFF" :light "#D35E4C" :dark "#7A7A7A"}
             {:note "#000000" :light "#E18C43" :dark "#999999"}
             {:note "#393939" :light "#E1B040" :dark "#B9B9B9"}])

(defn piano-key-width
  [{piano-keys :piano-keys w :w}]
  (.round js/Math (/ w (count piano-keys))))

(defn t->px
  [{start :start px-per-ms :px-per-ms} t]
  (* px-per-ms (- t start)))

(defn note-rect
  [{on :on off :off freq :freq :as note}
   {px-per-ms :px-per-ms playhead :playhead :as instrument}]
  (let [piano-key-w (piano-key-width instrument)]
    {:x (* piano-key-w (get-in instrument [:piano-keys freq :n]))
     :y (+ (t->px instrument on)
           (/ (-> instrument :h) 2))
     :w piano-key-w
     :h (- (t->px instrument (or off playhead))
           (t->px instrument on))}))

(defn screen-rect
  [{w :w h :h playhead :playhead :as instrument}]
  {:x 0 :y (t->px instrument playhead) :w w :h h})

(defn draw-note
  [draw-ctx instrument note]
  (let [{x :x y :y w :w h :h} (note-rect note instrument)]
    (set! (.-fillStyle draw-ctx) "white")
    (.fillRect draw-ctx x y w h)))

(defn colliding?
  [r1 r2]
  (not (or (< (+ (:x r1) (:w r1)) (:x r2))
           (< (+ (:y r1) (:h r1)) (:y r2))
           (> (:x r1) (+ (:x r2) (:w r2)))
           (> (:y r1) (+ (:y r2) (:h r2))))))

(defn on-screen?
  [instrument note]
  (colliding? (note-rect note instrument)
              (screen-rect instrument)))

(defn draw-notes
  [draw-ctx instrument]
  (doseq [note (filter (partial on-screen? instrument)
                       (-> instrument :notes))]
    (draw-note draw-ctx instrument note)))

(defn draw-piano-keys
  [draw-ctx {w :w h :h playhead :playhead :as instrument}]
  (let [piano-keys (-> instrument :piano-keys)
        piano-key-w (piano-key-width instrument)]
    (doseq [[n [freq piano-key]] (map-indexed vector piano-keys)]
      (set! (.-fillStyle draw-ctx)
            ((if (piano-key :on?) :light :dark) (nth colors (mod n (count colors)))))
      (.fillRect draw-ctx
                 (* n piano-key-w)
                 (t->px instrument playhead)
                 piano-key-w h))))

(defn draw-instrument
  [draw-ctx {w :w h :h playhead :playhead :as instrument}]
  (.save draw-ctx)
  (.translate draw-ctx 0 (-> (t->px instrument playhead) -))
  (.clearRect draw-ctx 0 0 w h)
  (draw-piano-keys draw-ctx instrument)
  (draw-notes draw-ctx instrument)
  (.restore draw-ctx))

(defn touch->note
  [x instrument]
  (let [note-index (.floor js/Math (/ x (piano-key-width instrument)))]
    (nth (-> instrument :piano-keys keys) note-index)))

(defn create-note-synth
  [freq]
  (timbre "adsr"
          (js-obj "a" 5 "d" 10000 "s" 0 "r" 500)
          (timbre "fami" (js-obj "freq" freq "mul" 0.1))))

(defn create-instrument
  [scale]
  (let [start (.getTime (js/Date.))]
    {:piano-keys (into (sorted-map) (map-indexed (fn [i freq] [freq {:n i :on? false}])
                                                 scale))
     :notes ()
     :w 0 :h 0 :sound-ready false
     :px-per-ms 0.04
     :cur-touches {}
     :scrolling? false
     :start start
     :playhead start}))

(defn add-synths-to-instrument
  [instrument]
  (assoc (reduce (fn [a x] (assoc-in a [:piano-keys x :synth] (create-note-synth x)))
                 instrument
                 (-> instrument :piano-keys keys))
    :sound-ready true))

(defn play-piano-key
  [instrument freq]
  (println "play")
  (.play (.bang (get-in instrument [:piano-keys freq :synth])))
  (assoc-in instrument [:piano-keys freq :on?] true))

(defn stop-piano-key
  [instrument freq]
  (if (get-in instrument [:piano-keys freq :on?])
    (do
      (println "stop" (get-in instrument [:piano-keys freq :synth]))
      (.release (get-in instrument [:piano-keys freq :synth]))
      (assoc-in instrument [:piano-keys freq :on?] false))
    instrument))

(defn touch-data->touches [touch-data]
  (let [event (.-event_ touch-data)
        touches (js->clj (.-changedTouches event) :keywordize-keys true)]
    (map (fn [x] {:type (.-type event)
                  :touch-id (:identifier x)
                  :position {:x (:clientX x) :y (:clientY x)}
                  :time (.-timeStamp event)})
         (for [x (range (:length touches))] ((keyword (str x)) touches)))))

(defn touch->freq
  [instrument touch]
  (touch->note (get-in touch [:position :x])
               instrument))

(defn maybe-init-synths
  [instrument]
  (if (:sound-ready instrument)
    instrument
    (add-synths-to-instrument instrument)))

(defn filter-type
  [type touches]
  (filter (fn [t] (= (t :type) type)) touches))

(defn reduce-val->>
  [f coll val]
  (reduce f val coll))

(defn touches->notes
  [{playhead :playhead :as instrument} touches]
  (->> touches
       (map (fn [t] (assoc t :freq (touch->freq instrument t))))
       (remove (fn [t] (get-in instrument [:piano-keys (t :freq) :on?])))
       (map (fn [t] {:freq (t :freq)
                     :on playhead
                     :off nil
                     :touch-id (t :touch-id)}))))

(defn start-notes
  [touches {notes :notes :as instrument}]
  (let [new-notes (touches->notes instrument (filter-type "touchstart" touches))]
    (reduce play-piano-key
            (assoc instrument :notes (concat new-notes notes))
            (map (fn [n] (n :freq)) new-notes))))

(defn end-notes
  [touches {notes :notes playhead :playhead :as instrument}]
  (let [off-touch-ids (->> (filter-type "touchend" touches)
                           (map :touch-id)
                           set)
        off-freqs (->> notes
                       (filter (fn [n] (contains? off-touch-ids (n :touch-id))))
                       (map :freq))]
    (-> instrument
        (assoc :notes (map (fn [{touch-id :touch-id :as n}]
                             (if (contains? off-touch-ids touch-id)
                               (assoc n :off playhead)
                               n))
                           notes))
        (->> (reduce-val->> stop-piano-key off-freqs)))))

(defn max-scroll-distance
  [touches instrument]
  (let [distances (map (fn [t]
                         (- (get-in instrument [:cur-touches (t :touch-id) :position :y])
                            (-> t :position :y)))
                       (filter-type "touchmove" touches))]
    (first (sort (fn [a b] (max (.abs js/Math a) (.abs js/Math b))) distances))))

(defn record-touches
  [touches instrument]
  (let [starts (filter-type "touchstart" touches)
        moves (filter-type "touchmove" touches)
        ends (filter-type "touchend" touches)]
    (-> instrument
        (update-in [:cur-touches] into (map vector (map :touch-id starts) starts))
        (update-in [:cur-touches] into (map vector (map :touch-id moves) moves))
        (update-in [:cur-touches] (fn [c] (apply dissoc c (map :touch-id ends)))))))

(defn scroll
  [touches {playhead :playhead :as instrument}]
  (if-let [scroll-distance (max-scroll-distance touches instrument)]
    (let [delta-y (/ scroll-distance (instrument :px-per-ms))]
      (-> instrument
          (assoc :scrolling? true)
          (assoc :playhead (+ playhead delta-y))))
    (assoc instrument :scrolling? false)))

(defn delete-scrolled-notes
  [touches {notes :notes :as instrument}]
  (let [touchmoves (filter-type "touchmove" touches)
        scroll-touch-ids (set (map :touch-id touchmoves))
        scroll-touch-freqs (map (partial touch->freq instrument) touchmoves)]
    (-> instrument
        (assoc :notes (remove (fn [t]
                                (and (nil? (t :off))
                                     (contains? scroll-touch-ids (t :touch-id))))
                              notes))
        (->> (reduce-val->> stop-piano-key scroll-touch-freqs)))))

(defn handle-scrolling
  [touches instrument]
  (->> instrument
       (scroll touches)
       (record-touches touches)
       (delete-scrolled-notes touches)))

(defn fire-touch-data-on-instrument
  [instrument data]
  (let [touches (touch-data->touches data)]
    (->> instrument
         maybe-init-synths
         (start-notes touches)
         (end-notes touches)
         (handle-scrolling touches))))

(defn update-size [instrument canvas-id]
  (let [{w :w h :h :as window-size} (util/get-window-size)]
    (do (util/set-canvas-size! canvas-id window-size)
        (.scrollTo js/window 0 0) ;; Safari leaves window part scrolled down after turn
        (assoc (assoc instrument :h h) :w w))))

(defn create-touch-input-channel
  [canvas-id]
  (async/merge [(util/listen (dom/getElement canvas-id) :touchstart)
                (util/listen (dom/getElement canvas-id) :touchend)
                (util/listen (dom/getElement canvas-id) :touchmove)]))

(defn step-time
  [instrument delta]
  (if (not (instrument :scrolling?))
    (assoc instrument :playhead (+ (instrument :playhead) delta))
    instrument))

(let [canvas-id "canvas"
      c-instrument (chan (sliding-buffer 1))
      c-orientation-change (util/listen js/window :orientation-change)
      c-touch (create-touch-input-channel canvas-id)
      frame-delay 16]

  (go
   (let [draw-ctx (util/get-ctx canvas-id)]
     (util/set-canvas-size! canvas-id (util/get-window-size))
     (loop [instrument (<! c-instrument)
            timer (timeout frame-delay)]
       (let [[data c] (alts! [c-instrument timer])]
         (condp = c
           c-instrument (recur data timer)
           (do (draw-instrument draw-ctx instrument)
               (recur instrument (timeout frame-delay))))))))

  (go
   (loop [instrument (update-size (create-instrument (scales/c-minor)) canvas-id)]
     (>! c-instrument instrument)
     (let [[data c] (alts! [c-touch c-orientation-change (timeout frame-delay)])]
       (condp = c
         c-orientation-change (recur (update-size instrument canvas-id))
         c-touch (recur (fire-touch-data-on-instrument instrument data))
         (recur (step-time instrument frame-delay)))))))
