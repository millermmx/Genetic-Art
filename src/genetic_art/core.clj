(ns genetic_art.core
  (:gen-class))

;; Use these for matrix manipulation and determinants
(require '[clojure.core.matrix :as m])
(require '[clojure.core.matrix.operators :as m-ops])

(m/set-current-implementation :vectorz)

;; Use these for image processing
(use 'mikera.image.core)
(use 'mikera.image.colours)
(require '[mikera.image.filters :as filt])


;;;;;;;;;;
;; Examples

; An examplePush state
(def example-push-state
  {:exec '()
   :integer '(1 2 3 4 5 6 7)
   :image '()
   :input {:in1 4}})

(def empty-push-state
  {:exec '()
   :integer '()
   :image '()
   :input {}
   :bool '()})

;;;;;;;;;;
;; Instructions must all be either functions that take one Push
;; state and return another or constant literals.

(def init-instructions
  (list
   'exec_dup
   'exec_if
   'invert_colors
   'laplace_filter
   'emboss_filter
   'edge_filter
   'laplace_filter
   'noise_filter
   'scramble_grid
   'section-and
   'section-or
   'section-xor
   'hsplit_combine
   'section-rotate
   'section-rotate
   'section-rotate
   true
   false
   1
   ))


;;;;;;;;;;;;;;;
;; Utilities ;;
;;;;;;;;;;;;;;;

(defn push-to-stack
  "Pushes item onto stack in state, returning the resulting state."
  ;; We added a clause that deals with pushing to input stack, it reads the keys in the stack,
  ;;  and it will add an input at in(# inputs + 1)
  ;; ex: (push-to-stack {:input {:in1 3 :in2 6}} :input 10) --> {:input {:in1 3 :in2 6 :in3 10}}
  ;; We utilized this in the in1 function
  [state stack item]
  (if (= stack :input)
    (assoc state stack (assoc (state stack) (keyword (str "in" (+ 1 (count (keys (state stack))))))
                        item))
    (assoc state stack (conj (state stack) item))))

(defn empty-stack?
  "Returns true if the stack is empty in state."
  [state stack]
  (zero? (count (state stack))))

(defn pop-stack
  "Removes top item of stack, returning the resulting state."
  [state stack]
  (if (empty-stack? state stack)
    state
    (assoc state stack (rest (state stack)))))

(defn peek-stack
  "Returns top item on a stack. If stack is empty, returns :no-stack-item"
  [state stack]
  (if (empty-stack? state stack)
    :no-stack-item
    (first (state stack))))


(defn get-args-from-stacks
  "Takes a state and a list of stacks to take args from. If there are enough args
  on each of the desired stacks, returns a map of the form {:state :args}, where
  :state is the new state with args popped, and :args is a list of args from
  the stacks. If there aren't enough args on the stacks, returns :not-enough-args."
  [state stacks]
  (loop [state state
         stacks stacks
         args '()]
    (if (empty? stacks)
      {:state state :args (reverse args)}
      (let [stack (first stacks)]
        (if (empty-stack? state stack)
          :not-enough-args
          (recur (pop-stack state stack)
                 (rest stacks)
                 (conj args (peek-stack state stack))))))))

(defn make-push-instruction
  "A utility function for making Push instructions. Takes a state, the function
  to apply to the args, the stacks to take the args from, and the stack to return
  the result to. Applies the function to the args (taken from the stacks) and pushes
  the return value onto return-stack in the resulting state."
  [state function arg-stacks return-stack]
  (let [args-pop-result (get-args-from-stacks state arg-stacks)]
    (if (= args-pop-result :not-enough-args)
      state
      (let [result (apply function (reverse (:args args-pop-result)))
            new-state (:state args-pop-result)]
        (push-to-stack new-state return-stack result)))))

(defn image_to_matrix
  "Takes a buffered image and returns a matrix of the integer values for the pixels.
  Each array in the matrix array represents a row."
  [img]
  (m/matrix (into [] (map #(into [] %)(partition (width img) (get-pixels img))))))

(defn prog-to-individual
  "Takes a program and creates an individual with no error values or with error values if given."
  ([prog]  ;; Just converts program to individual with no errors
  {:program prog
   :errors '[]
   :total-error 0})
  ([prog error-list total-error]  ;; Converts a program to an individual with its errors
   {:program prog
    :errors (first error-list)
    :total-error (first error-list)}))

(defn abs
  "Returns the absolute value of a number x"
  [x]
  (if (< x 0)
    (*' -1 x)
    x))

;;;;;;;;;;;;;;;;;;;
;; Utilities End ;;
;;;;;;;;;;;;;;;;;;;

;;;;;;;;;;;;;;;;;;
;; Instructions ;;
;;;;;;;;;;;;;;;;;;


;;;;;;;;;;;;;;;;;;;;
;; Exec instructions

(defn exec_dup
  "Duplicates the first element on the exec stack and places it in front"
  [state]
  (if (zero? (count (get state :exec))) ;; make sure the stack isnt empty
    state
    (if (= (peek-stack state :exec) 'exec_dup) ;; deals with case (exec_dup exec_dup) infinite looping
      state
      (assoc state :exec (conj (get state :exec) (peek-stack state :exec))))))

(defn exec_if
  "If bool stack has true, first item on exec stack, else second item"
  [state]
  (if (or (zero? (count (get state :bool))) (> 1 (count (get state :exec)))) ;; need enough elements to work with
    state
    (if (peek-stack state :bool)
      ;; remove second element of exec, popping from bool stack also
      (assoc (pop-stack state :bool) :exec (conj (rest (rest (get state :exec))) (peek-stack state :exec)))
      ;; remove first element of exec, popping from bool stack also
      (assoc (pop-stack state :bool) :exec (rest (get state :exec))))))

;;;;;;;;;;;;;;;;;;;;;
;; Image manipulation

(defn vsplit_combine_list
  "Splits two input images in half and combines them, half of image A, half of image B.
  Split column is decided randomly here."
  [ls1 ls2 rand-index width height]
  (loop [new-lst '()
         index 0
         images (list ls1 ls2)]
        (if (= index (count ls1))
          new-lst
          (if (= index width)
            (recur (concat new-lst (list (nth (first (reverse images)) index)))
                   (+ index 1)
                   (reverse images))
            (if (and (zero? (mod index rand-index)) (not (zero? index)))
              (recur (concat new-lst (list (nth (first (reverse images)) index)))
                     (+ index 1)
                     (reverse images))
              (recur (concat new-lst (list (nth (first images) index)))
                     (+ index 1)
                     images))))))

(defn vsplit_helper
  [state img2 new-list]
  (push-to-stack (pop-stack (pop-stack state :image) :image) :image img2))

(defn vsplit_combine
  "Splits two images horizontally and combines them.
  Split row is decided randomly here."
  [state]
  (if (< (count (get state :image)) 2)
    state
    (let [img1 (peek-stack state :image)
          img2 (peek-stack (pop-stack state :image) :image)
          rand-index (+ 1 (rand-int (- (width img1) 2)))
          sub-pixels1 (get-pixels (sub-image img1 0 0 rand-index (height img1)))
          sub-pixels2 (get-pixels (sub-image img2 rand-index 0 (- (width img1) rand-index) (height img1)))
          ]
      (vsplit_helper state img2 (int-array (vsplit_combine_list sub-pixels1 sub-pixels2 rand-index (width img1) (height img1)))))))


(defn hsplit_combine
  "Splits two images horizontally and combines them.
  Split row is decided randomly here."
  [state]
  (if (< (count (get state :image)) 2)
    state
    (let [img1 (peek-stack state :image)
          img2 (peek-stack (pop-stack state :image) :image)
          rand-index (+ 1 (rand-int (- (height img1) 2)))
          sub-pixels1 (get-pixels (sub-image img1 0 0 (width img1) rand-index))
          sub-pixels2 (get-pixels (sub-image img2 0 rand-index (width img1) (- (height img1) rand-index)))
          ]
      (set-pixels img2 (int-array (concat sub-pixels1 sub-pixels2)))
      (push-to-stack (pop-stack (pop-stack state :image) :image) :image img2) )))

(defn replace-img-helper
  [img
   sub-img
   start-x
   start-y
   y]
  (loop [x start-x]
    (if (< x (+ start-x (width sub-img)))
      (do
        (set-pixel img
                   x y
                   (get-pixel sub-img
                              (- x start-x) (- y start-y)))
        (recur (inc x))))))
  
(defn replace-img-section
  [img
   sub-img
   start-x
   start-y]
  (loop [y start-y]
    (if (< y (+ start-y (height sub-img)))
      (do 
        (replace-img-helper img sub-img start-x start-y y)
        (recur (inc y)))))
  img)
    
(defn section-rotate
  [state]
  (let [img (peek-stack state :image)
        rand-x (rand-int (width img))
        rand-y (rand-int (height img))
        rand-dim (inc (rand-int (min (dec (- (width img) rand-x)) (dec (- (height img) rand-y)))))
        rotated-section (rotate (sub-image img rand-x rand-y rand-dim rand-dim) (rand-nth '(90 180 270)))]
  
    (push-to-stack (pop-stack state :image) :image
                   (replace-img-section img rotated-section rand-x rand-y))))


(defn apply-bit-operators
  "Helper function for the logical operators. Applies operators to the given list."
  [ls op]
  (apply #((eval op) % %2) ls))

(defn image-bitwise-helper
  "Helper function for applying bitwise operators to two images"
  [state op]
  (if (>= 2 (count (get state :image)))
    state
    (let [img1 (peek-stack state :image)
          img2 (peek-stack (pop-stack state :image) :image)
          pixels1 (int-array (get-pixels img1))
          pixels2 (int-array (get-pixels img2))]
      (set-pixels img2 (int-array (map #(apply-bit-operators % op) (map list pixels1 pixels2))))
      (push-to-stack (pop-stack (pop-stack state :image) :image) :image img2) )))

(defn section-and
  "Takes a state, takes two random rectangles out of
  two images of random dimensions (same for each image) and performs a bitwise AND between
  all of the pixels in rectangle 1 and rectangle 2.  Returns modified image 2"
  [state]
  (image-bitwise-helper state 'bit-and))

(defn section-or
  "Takes a state, takes two random rectangles out of
  two images of random dimensions (same for each image) and performs a bitwise OR between
  all of the pixels in rectangle 1 and rectangle 2.  Returns modified image 2"
  [state]
  (image-bitwise-helper state 'bit-or))

(defn section-xor
  "Takes a state, takes two random rectangles out of
  two images of random dimensions (same for each image) and performs a bitwise XOR between
  all of the pixels in rectangle 1 and rectangle 2.  Returns modified image 2"
  [state]
  (image-bitwise-helper state 'bit-xor))


(defn rand-color-input
  "Returns a random color. Has input for being used with filter/map."
  [x]
  (rand-colour))

(defn invert_colors
  "Inverts colors of the image"
  [state]
  (if (empty-stack? state :image)
    state
    (assoc (pop-stack state :image) :image (conj
                                            (get (pop-stack state :image) :image)
                                            (filter-image (peek-stack state :image) (filt/invert))))))

(defn laplace_filter
  "Applies a laplace filter to the image"
  [state]
    (if (empty-stack? state :image)
    state
    (assoc (pop-stack state :image) :image (conj
                                            (get (pop-stack state :image) :image)
                                            (filter-image (peek-stack state :image) (filt/laplace))))))

(defn emboss_filter
  "Applies a emboss filter to the image"
  [state]
  (if (empty-stack? state :image)
    state
    (assoc (pop-stack state :image) :image (conj
                                            (get (pop-stack state :image) :image)
                                            (filter-image (peek-stack state :image) (filt/emboss))))))

(defn edge_filter
  "Applies an edge filter to the image"
  [state]
  (if (empty-stack? state :image)
    state
    (assoc (pop-stack state :image) :image (conj
                                            (get (pop-stack state :image) :image)
                                            (filter-image (peek-stack state :image) (filt/edge))))))


(defn noise_filter
  "Applies a noise filter to the image"
  [state]
  (if (empty-stack? state :image)
    state
    (assoc (pop-stack state :image) :image (conj
                                            (get (pop-stack state :image) :image)
                                            (filter-image (peek-stack state :image) (filt/noise))))))

(defn three-egg-scramble
  "Takes a split list and turns it into buffered image from the state"
  [state lst]
  (let [pixels (int-array (apply concat (shuffle lst)))
        img (peek-stack state :image)]
    (set-pixels (peek-stack state :image) (int-array (apply concat (shuffle lst))))
    state))


(defn scramble_grid
  "Splits the image into smaller rectangles of random size and randomly places all of them in
  a new spot"
  [state]
  (if (empty? (get state :image))
    state
    (let [img (first (get state :image))
          wid (quot (width img) 2)
          hght (quot (height img) 2)]
      (loop [split_list '()
             x 0
             y 0]
        (if (>= y (* 2 hght))
          (three-egg-scramble state split_list)
          (if (>= x (* 2 wid))
            (recur split_list
                   0
                   (+' y hght))
            (recur (conj split_list (get-pixels (sub-image img x y wid hght)))
                   (+' x wid)
                   y)))))))

;;;;;;;;;;;;;;;;;;;;;;
;; Instructions End ;;
;;;;;;;;;;;;;;;;;;;;;;


;;;;;;;;;;;;;;;;;
;; Interpreter ;;
;;;;;;;;;;;;;;;;;

(defn load-exec
  "Places a program in the exec stack in a map"
  [program state]
  (assoc state :exec (concat program (state :exec))))


(defn interpret-one-step
  "Helper function for interpret-push-program.
  Takes a Push state and executes the next instruction on the exec stack,
  or if the next element is a literal, pushes it onto the correct stack.
  Returns the new Push state."
  [push-state]
  (if (not (empty-stack? push-state :exec))  ;; If it is empty, return the push-state
    (let [element (peek-stack push-state :exec)
          popped-state (pop-stack push-state :exec)] ;; Else lets see whats the first element
      (cond
        (instance? Boolean element) (push-to-stack popped-state :bool element)
        (integer? element) (push-to-stack popped-state :integer element) ;; Number
        (seq? element) (interpret-one-step (load-exec element popped-state)) ;; Nested isntructions
        :else ((eval element) popped-state)))
    push-state))

(defn interpret-push-program
  "Runs the given program starting with the stacks in start-state. Continues
  until the exec stack is empty. Returns the state of the stacks after the
  program finishes executing."
  [program start-state]
  (let [state (load-exec program start-state)]
    (loop [state state] ;; Loop until the :exec stack is empty
      (if (empty-stack? state :exec)
          state
          (recur (interpret-one-step state)))))) ;; Recur interpret each step


;;;;;;;;;;;;;;;;;;;;;
;; Interpreter End ;;
;;;;;;;;;;;;;;;;;;;;;

;;;;;;;;;;
;; GP   ;;
;;;;;;;;;;

(defn make-random-push-program
  "Creates and returns a new program. Takes a list of instructions and
  a maximum initial program size."
  [instructions max-initial-program-size]
  (let [program-size (+ (rand-int max-initial-program-size) 1)]
    (repeatedly program-size #(rand-nth instructions))))


(defn shuffle-order
  "Returns a list of indicies that are in the range of test-cases size, without replacement
  so that we can shuffle the test cases for each individual in the same way."
  [individual]
  (let [test-size (count (:errors individual))]
    (shuffle (range test-size))))

(defn shuffle-test-cases
  "Returns a shuffled list of test-cases for an individual"
  [individual order]
  (let [shuffled (map #(nth (:errors individual) %) order)] 
    (assoc individual :errors shuffled)))

(defn find-lowest-error
  "Finds the lowest error in the population for a given test case"
  [population case]
  (apply min (map #(nth % case) (map #(:errors %) population))))


(defn errors-stddev
  [population
   case]
  (let [errors-list (map #(nth (:errors %) case) population)
        mean-error (/ (apply + errors-list) 2)]
    (/ (apply + (map #(Math/pow (- % mean-error) 2) errors-list)) 2)))
    
(defn lexicase-selection
  "Takes a population of evaluated individuals. Goes through test
  cases in random order.  Removes any individuals with error value on
  given test case greater than best error in population.  Once we are done
  going through test cases, random remaining individual will be returned for
  reproduction."
  [population tournament-size]
  (let [order (shuffle-order (first population))
        new-pop (map #(shuffle-test-cases % order) population)]
    (loop [candidates new-pop
           case 0]

      (if (empty? candidates)
        (rand-nth population)
      (if (= (count candidates) 1)
        (first candidates)
        (if (>= case (count order))
          (rand-nth candidates)
          (let [lowest-error (find-lowest-error candidates case)
                new-candidates (remove #(= % nil)
                                       (map (fn [candidate]
                                              (if (<= (nth (:errors candidate) case) 
                                                      (+ lowest-error (errors-stddev candidates case)))
                                                
                                                  candidate)) candidates))]
            (recur new-candidates
                   (inc case)))))))))



(defn tournament-selection
  "Selects an individual from the population using a tournament. Returned
  individual will be a parent in the next generation. Can use a fixed
  tournament size."
  [population
   tournament-size]
  (let [tournament-members (repeatedly tournament-size #(rand-nth population))]
    ;; This finds the individual with the smallest total-error
    (apply min-key #(% :total-error) tournament-members)))

(defn prob-pick
  "Returns true [prob] amount of the time.  Need second case so we can use with filter."
  ([prob] (< (rand) prob))
  ([prob x] (prob-pick prob)))

;;;;;;;;;;;;;;;;;;;;;;;;
;; GP Operators       ;;
;;;;;;;;;;;;;;;;;;;;;;;;
;; ------------------------------------
;; ---------- Crossovers ------------------

(defn uniform-crossover
  "Crosses over two programs (note: not individuals) using uniform crossover.
  Returns child program."
  [prog-a
   prog-b]
  (loop [prog-a prog-a
         prog-b prog-b
         new '()]
    (if (empty? prog-a) ;; If one is empty then 50% chance to take the others instruction at that index
      (concat new (filter #(prob-pick 0.5 %) prog-b))
      (if (empty? prog-b)
        (concat new (filter #(prob-pick 0.5 %) prog-a))
        (recur (rest prog-a)
               (rest prog-b)
               (if (= (rand-int 2) 0) ;; Pick one of the programs instructions and add to child
                 (apply list (conj (apply vector new) (first prog-a)))
                 (apply list (conj (apply vector new) (first prog-b)))))))))



(defn pick-indices
  [prog]
  (let [indices (range (count prog))        
        first (rand-nth indices)
        second (if (> (count indices) 1)
                 (rand-nth (remove #(= % first) indices))
                 first)]
    (sort (list first second))))

(defn two-point-crossover
  [prog-a
   prog-b]
  (let [indices-a (pick-indices prog-a)
        indices-b (pick-indices prog-b)]
    (concat (subvec (vec prog-b) 0 (first indices-b))
            (subvec (vec prog-a) (first indices-a) (last indices-a))
            (subvec (vec prog-b) (last indices-b)))))


;; ---- Mutations ------------------------
(defn uniform-addition
  "Randomly adds new instructions before every instruction (and at the end of
  the program) with some probability. Returns child program."
  [prog
   instructions]
  ;; Added instructions as a parameter
  (let [child (reduce concat
                      (map (fn [x]
                             (if (prob-pick 0.05)
                               (list x (nth instructions (rand-int (count instructions))))
                               (list x))) prog))]
    (if (prob-pick 0.05)
      (conj child (nth instructions (rand-int (count instructions))))
      child)))


(defn uniform-deletion
  "Randomly deletes instructions from program at some rate. Returns child program."
  [program]
  (filter #(not (prob-pick 0.05 %)) program))

;;;;;;;;;;;;;;;;;;
;; End operators
;;;;;;;;;;;;;;;;;;


;;;;;;;;;;;;
;; New Population Creation Functions

(defn remove-selected
  [population
   parent1]
  (let [ind (.indexOf (map #(:program %) population) parent1)]
    (concat (subvec (vec population) 0 ind) (subvec (vec population) (inc ind)))))


(defn select-and-vary
  "Selects parent(s) from population and varies them, returning
  a child individual (note: not program). Chooses which genetic operator
  to use probabilistically. Gives 50% chance to crossover,
  25% to uniform-addition, and 25% to uniform-deletion."
  [population
   tournament-size
   parent-select-fn]
  (let [seed (rand)    ;; Want to keep the same random number to base decision on
        parent1 (:program (parent-select-fn population tournament-size))    ;; Only want to select parents once, so save them
        new-pop (remove-selected population parent1)
        parent2 (:program (parent-select-fn population tournament-size))]

    (cond
      (< seed 0.5) (if (<= seed 0.25)
                     (uniform-crossover parent1 parent2)
                     (two-point-crossover parent1 parent2))
      (and (>= seed 0.5) (< 0.75)) (uniform-addition parent1 parent2)
      (>= seed 0.75) (uniform-deletion parent1))))

(defn init-population
  "Initialize a population of random programs of a certain maximum size"
  [size max-program-size instructions]
  ;; Creates individuals with no errors associated with them yet
  (map #(prog-to-individual %) (take size (repeatedly #(make-random-push-program instructions max-program-size)))))

(defn load-images
  "Loads a bunch of BufferedImages into a list from
  a bunch of image file names"
  [& images]
  (map #(load-image-resource %) images))

(defn get-child-population
  "Creates the next generation using select-and-vary function on the previous generation"
  [population population-size tournament-size parent-select-fn]
  (loop [new-pop '()]
    (if (= (count new-pop) population-size)
      new-pop
      (recur (conj new-pop
                   (select-and-vary population tournament-size parent-select-fn))))))



;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Evaluations
;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn load-initial-state
  "Loads the input images into the state for the inception of the programs evaluation."
  [state input-images]
  (loop [iter 0
         state state]
    (if (= iter (count input-images))
      state
      (recur (inc iter)
             (push-to-stack state :image (nth input-images iter))))))

(defn multiple-inputs
  "Pushes multiple inputs to the input stack."
  [state lst]
  (loop [iter 0
         state state]
    (if (= iter (count lst))
      state
      (recur (+ 1 iter)
             (push-to-stack state :input (nth lst iter))))))

(defn evaluate-one-case
  "Evaluates a single case for regression error function"
  [individual initial-push-state input-images]
  (interpret-push-program (:program individual) initial-push-state))

(defn abs-difference-in-solution-lists
  "Computes the differences in the solutions for the input programs, returns errors list"
  ;; Ex: solution:(1 2 3 4), program solution:(4 4 4 4), output of this function: (3 2 1 0)
  [l1 l2]
  (if (zero? (count l2))
    (repeat (count l1) 100000)
    (loop [l1 l1
           l2 l2
           final '()]
      (if (= (count l1) 0)
        (reverse final)
        (recur (rest l1)
               (rest l2)
               (conj final (abs (- (first l1) (first l2)))))))))


(defn get-solution
  "Gets the list of solution for a test case"
  [individual initial-push-state input-images]
  (evaluate-one-case individual initial-push-state input-images))

(defn image-determinant
  [img]
  (m/det (image_to_matrix img)))


;; CITE: Taken from https://github.com/clojure/math.combinatorics. Could
;; have imported as a dependency, but I only needed the cartesian product
;; function, so I just copied and pasted here.
(defn cartesian-product
  "All the ways to take one item from each sequence"
  [& seqs]
  (let [v-original-seqs (vec seqs)
        step
        (fn step [v-seqs]
          (let [increment
                (fn [v-seqs]
                  (loop [i (dec (count v-seqs)), v-seqs v-seqs]
                    (if (= i -1) nil
                      (if-let [rst (next (v-seqs i))]
                        (assoc v-seqs i rst)
                        (recur (dec i) (assoc v-seqs i (v-original-seqs i)))))))]
            (when v-seqs
              (cons (map first v-seqs)
                    (lazy-seq (step (increment v-seqs)))))))]
    (when (every? seq seqs)
      (lazy-seq (step v-original-seqs)))))

(defn sectionalize
  "Splits an image into a list of 2x2 sections.  Does this by
  creating a range of starting coordinates for x and y, then taking
  the cartesian product of those two ranges to get a sequence of
  coords to start the sections at.  Can change the size of the sections
  by changing x-section-size and y-section-size."
  [img]
  (let [x-section-size (quot (width img) 10)
        y-section-size (quot (height img) 10)
        x-inds (range 0 (width img) x-section-size)
        y-inds (range 0 (height img) y-section-size)
        coords (cartesian-product x-inds y-inds)]
    (map #(sub-image img (first %) (last %) x-section-size y-section-size) coords)))

(defn test-case-list
  "Just a wrapper for getting the list of determinants for the target image.
  The resulting list will be used both in the error function and in lexicase
  selection."
  [target-image]
  (map image-determinant (sectionalize target-image)))


(defn error-function
  [individual initial-push-state input-images target-image]
  (let [target-list (test-case-list target-image)
        result (peek-stack (get-solution individual initial-push-state input-images) :image)
        program-list (if (identical? result :no-stack-item)
                       (repeat (count target-list) 10000000000)
                       (test-case-list result)) ;; List solutions for given individual
        errors (abs-difference-in-solution-lists target-list program-list)]
    {:program (:program individual)
     :errors errors
     :total-error (reduce + errors)}))

;;;;;;;;;;;;;;;;;;;;;;;
;; Reporting
;;;;;;;;;;;;;;;;;;;;;;;

(defn report
  "Reports information on the population each generation. Should look something
  like the following (should contain all of this info; format however you think
  looks best; feel free to include other info)."
  [population generation input-images]
  (println)
  (println "-------------------------------------------------------")
  (printf  "                    Report for Generation %s           " generation)
  (println)
  (println "-------------------------------------------------------")
  
  (let [best-prog (apply max-key #(get % :total-error) population)
        img (peek-stack (get-solution best-prog (load-initial-state empty-push-state (input-images)) input-images) :image)]
    (printf "Best program: ")
    (println (best-prog :program)) ;; Wanted to print the actual program, not just the location
    (println)
    (printf "Best program size: %s" (count (get best-prog :program)))
    (println)
    (printf "Best total error: %s" (get best-prog :total-error))
    (println)
    (printf "Best errors: %s" (get best-prog :errors))
    (write (resize img 1000 1000)
           (str "results/" (width img) "/" (new java.util.Date)   "_gen" generation ".png")
           "png" :quality 1.0 :progressive true)))

;; --------------------------------------------------


(defn push-gp
  "Main GP loop. Initializes the population, and then repeatedly
  generates and evaluates new populations. Stops if it finds an
  individual with 0 error (and should return :SUCCESS, or if it
  exceeds the maximum generations (and should return nil). Should print
  report each generation.
  --
  The only argument should be a map containing the core parameters to
  push-gp. The format given below will decompose this map into individual
  arguments. These arguments should include:
   - population-size
   - max-generations
   - error-function
   - instructions (a list of instructions)
   - max-initial-program-size (max size of randomly generated programs)"
  [{:keys [population-size max-generations error-function instructions max-initial-program-size
           initial-push-state input-images target-image parent-select-fn]}]
  (loop [count 0
         population (map #(error-function % initial-push-state input-images target-image)
                         (init-population population-size max-initial-program-size instructions))]
    (report population count input-images)
    (if (>= count max-generations) ;; If we reach max-generations, null, otherwise keep going
      :nil
      (if (= 0 (get (apply min-key #(get % :total-error) population) :total-error)) ;; Anyone with error=0?
        :SUCCESS
        (recur (+ count 1) ;; Recur by making new population, and getting errors
               (map #(error-function (prog-to-individual %) initial-push-state input-images target-image)
                    (get-child-population
                     (map #(error-function % initial-push-state input-images target-image) population)
                     population-size 10 parent-select-fn))))))) ;; Using a fixed tournament size of 20 for quick conversion


;;;;;;;;;;;;;;;;;;;;;;;;;
;;; System parameters
;;;;;;;;;;;;;;;;;;;;;;;;;

;;;;;;;;;;;;;;
;; Targets
; -------------

(def target10
  (load-image-resource "exp.jpg"))

(def targetDali100
  (load-image-resource "300dali2.jpg"))


(def target32
  (load-image-resource "32_insta.png"))

(def targetIcon100
  (load-image-resource "100_idk.png"))

;;;;;;;;;;;;;;;
;; Inputs
; --------------
(defn inputs10
  []
  (load-images "arrow_up.jpg" "btnPlus.png"))

(defn inputs128
  []
  (load-images "cars.jpg" "cars.jpg"))

(defn inputsDali100
  []
  (load-images "300dali1.jpg" "300trippy.png"))


(defn inputs32
  []
  (load-images "32_g+.png" "32_face.png" "32_twitter.png" "32_pin.png"))

(defn inputsIcons100
  []
  (map #(resize % 100 100)
       (load-images "300trippy.png" "300dali2.jpg" "300dali1.jpg" "100_fund.jpeg"
                    "100_nbc.png" "100_soccer.png" "100_icons.jpeg" "100_house.png"
                    "100_nyc.jpg" "100_sunset.jpg" "100_china.jpg" "480_loop.jpeg" "large_people.jpg" "large_water.jpg")))


;;;;;;;;;;;;;;;;;;;;;;;
;;;; Main controller
;;;;;;;;;;;;;;;;;;;;;;;

(defn -main
  [& args]
  (let [input-images inputsIcons100
        target-image targetIcon100]
    (binding [*ns* (the-ns 'genetic_art.core)]
    (push-gp {:instructions init-instructions
              :error-function error-function
              :max-generations 2
              :population-size 10
              :max-initial-program-size 30
              :initial-push-state (load-initial-state empty-push-state (input-images))
              :input-images input-images
              :target-image target-image
              :parent-select-fn lexicase-selection}))))

