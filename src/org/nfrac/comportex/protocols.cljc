(ns org.nfrac.comportex.protocols)

(defprotocol PHTM
  "A network of regions, forming Hierarchical Temporal Memory."
  (htm-activate [this in-value])
  (htm-learn [this])
  (htm-depolarise [this])
  (htm-export [this]))

(defn htm-step
  [this in-value]
  (-> this
      (htm-activate in-value)
      (htm-learn)
      (htm-depolarise)))

(defprotocol PRegion
  "Cortical regions need to extend this together with PTopological,
   PFeedForward, PTemporal, PParameterised."
  (region-activate [this ff-bits stable-ff-bits])
  (region-learn [this])
  (region-depolarise [this distal-ff-bits distal-fb-bits]))

(defn region-step
  ([this ff-bits]
     (region-step this ff-bits #{} #{} #{}))
  ([this ff-bits stable-ff-bits distal-ff-bits distal-fb-bits]
     (-> this
         (region-activate ff-bits stable-ff-bits)
         (region-learn)
         (region-depolarise distal-ff-bits distal-fb-bits))))

(defprotocol PFeedForward
  "A feed-forward input source with a bit set representation. Could be
   sensory input or a region (where cells are bits)."
  (ff-topology [this])
  (bits-value [this]
    "The set of indices of all active bits/cells.")
  (stable-bits-value [this]
    "The set of indices of active cells where those cells were
    predicted (so, excluding cells from bursting columns).")
  (source-of-bit [this i]
    "Given the index of an output bit from this source, return the
    corresponding local cell id as [col ci] where col is the column
    index. If the source is an input encoder, returns [i]."))

(defprotocol PFeedForwardMotor
  (ff-motor-topology [this])
  (motor-bits-value [this]))

(defprotocol PLayerOfCells
  (layer-activate [this ff-bits stable-ff-bits])
  (layer-learn [this])
  (layer-depolarise [this distal-ff-bits distal-fb-bits])
  (layer-depth [this]
    "Number of cells per column.")
  (bursting-columns [this]
    "The set of bursting column ids.")
  (active-columns [this]
    "The set of active column ids.")
  (active-cells [this]
    "The set of active cell ids.")
  (learnable-cells [this]
    "The set of active, learnable cell ids. These are the winning
    cells in each active column, and could be thought of as having
    more prolonged activation than other active cells.")
  (temporal-pooling-cells [this]
    "The collection of temporal pooling cells, i.e. those having some
    non-zero level of continuing temporal pooling excitation.")
  (predictive-cells [this]
    "The set of predictive cell ids.")
  (prior-predictive-cells [this]
    "The set of predictive cell ids from the previous timestep,
    i.e. their prediction can be compared to the current active
    cells."))

(defprotocol PSynapseGraph
  "The synaptic connections from a set of sources to a set of targets.
   Synapses have an associated permanence value between 0 and 1; above
   some permanence level they are defined to be connected."
  (in-synapses [this target-id]
    "All synapses to the target. A map from source ids to permanences.")
  (sources-connected-to [this target-id]
    "The collection of source ids actually connected to target id.")
  (targets-connected-from [this source-id]
    "The collection of target ids actually connected from source id.")
  (excitations [this active-sources stimulus-threshold]
    "Computes a map of target ids to their degree of excitation -- the
    number of sources in `active-sources` they are connected to -- excluding
    any below `stimulus-threshold`.")
  (bulk-learn [this seg-updates active-sources pinc pdec pinit]
    "Applies learning updates to a batch of targets. `seg-updates` is
    a sequence of SegUpdate records, one for each target dendrite
    segment."))

(defprotocol PSegments
  (cell-segments [this cell-id]
    "A vector of segments on the cell, each being a synapse map."))

(defprotocol PEncodable
  "Encoders need to extend this together with PTopological."
  (encode [this x]
    "Encodes `x` as a collection of distinct integers which are the on-bits.")
  (decode [this bit-votes n]
    "Finds `n` domain values matching the given bit set in a sequence
     of maps with keys `:value`, `:votes-frac`, `:votes-per-bit`,
     `:bit-coverage`, `:bit-precision`, ordered by votes fraction
     decreasing. The argument `bit-votes` is a map from encoded bit
     index to a number of votes, typically the number of synapse
     connections from predictive cells."))

(defprotocol PInputSource
  "Inputs need to extend this together with PFeedForward."
  (input-step [this in-value])
  (input-export [this]))

(defprotocol PRestartable
  (restart [this]
    "Returns this model (or model component) reverted to its initial
    state prior to any learning."))

(defprotocol PInterruptable
  (break [this]
    "Returns this model (or model component) without its current
    sequence state, forcing the following input to be treated as a new
    sequence. I.e. prevents learning distal connections to current
    cells, and cancels any temporal pooling potential."))

(defprotocol PTemporal
  (timestep [this]))

(defprotocol PParameterised
  (params [this]
    "A parameter set as map with keyword keys."))

(defprotocol PTopological
  (topology [this]))

(defprotocol PTopology
  "Operating on a regular grid of certain dimensions, where each
   coordinate is an n-tuple vector---or integer for 1D---and also has
   a unique integer index."
  (dimensions [this])
  (coordinates-of-index [this idx])
  (index-of-coordinates [this coord])
  (neighbours* [this coord outer-r inner-r])
  (coord-distance [this coord-a coord-b]))

(defn size
  "The total number of elements indexed in the topology."
  [topo]
  (reduce * (dimensions topo)))

(defn dims-of
  "The dimensions of a PTopological as an n-tuple vector."
  [x]
  (dimensions (topology x)))

(defn size-of
  "The total number of elements in a PTopological."
  [x]
  (size (topology x)))

(defn neighbours
  "Returns the coordinates away from `coord` at distances
  `inner-r` (exclusive) out to `outer-r` (inclusive) ."
  ([topo coord radius]
     (neighbours* topo coord radius 0))
  ([topo coord outer-r inner-r]
     (neighbours* topo coord outer-r inner-r)))

(defn neighbours-indices
  "Same as `neighbours` but taking and returning indices instead of
   coordinates."
  ([topo idx radius]
     (neighbours-indices topo idx radius 0))
  ([topo idx outer-r inner-r]
     (->> (neighbours* topo (coordinates-of-index topo idx)
                       outer-r inner-r)
          (map (partial index-of-coordinates topo)))))
