# Reading list for the algebraic refactor

Curated from the library at `studio:/Users/jpablo/Dropbox` (mostly under `articles/`).
Two goals drive the selection:

1. **Theoretical**: express dspy4s constructions (programs, signatures, optimizers, the Para prototype) in categorical terms, for its own sake.
2. **Practical**: replace the "kitchen sink of unconnected methods" with a few core concepts, combinators, and clear laws.

Paths below are relative to `studio:/Users/jpablo/Dropbox/`.

## Tier 1: category theory ∩ machine learning (directly on-topic)

These speak to exactly what the Para/`Program` prototype is doing: parametrized morphisms, bidirectional (lens-shaped) learning, optimization as structure.

| Item | Location |
|---|---|
| Compositional Deep Learning (Gavranović, 2019, arXiv 1907.08292) | `articles/Machine Learning/Probability & Statistics/Compositional Deep Learning - 1907.08292.pdf` |
| Coherence for lenses and open games (Hedges, arXiv 1704.02230) | `articles/Mathematics/Category Theory/1704.02230.pdf` |
| Morphisms of open games (Hedges, arXiv 1711.07059) | `articles/Mathematics/Category Theory/1711.07059.pdf` |
| Compositional Game Theory (Ghani, Hedges et al, 2018) | `articles/Mathematics/Category Theory/Compositional Game Theory - 2018.pdf` |
| Categories of Optics (Riley, 2018) | `articles/Mathematics/Category Theory/Categories of Optics (Riley) - 2018.pdf` |
| Profunctor Optics (Gibbons, 2016) | `articles/Mathematics/Category Theory/Profunctor Optics (Gibbons) - 2016.pdf` |
| Profunctor optics and traversals (Román, 2020) | `articles/Mathematics/Category Theory/Profunctor Optics and traversals optics (Román) - 2020.pdf` |
| Space-time tradeoffs of lenses and optics via higher category theory (arXiv 2209.09351) | `articles/Mathematics/Category Theory/2209.09351.pdf` |
| The Simple Essence of Automatic Differentiation (Elliott, 2018) | `articles/Computer Science/Functional Programming/The Simple Essence of Automatic Differentiation (Elliott) - 2018.pdf` |
| Geometric Deep Learning proto-book (Bronstein et al, arXiv 2104.13478) | `articles/Machine Learning/Deep Learning/2104.13478.pdf` |
| Algebraic Machine Learning | `articles/Machine Learning/Probability & Statistics/Algebraic Machine Learning.pdf` |
| Homotopy Theoretic and Categorical Models of Neural Information Networks (arXiv 2006.15136) | `articles/Mathematics/Category Theory/2006.15136.pdf` |
| Composing games into complex institutions | `articles/Economics & Finance/Game Theory/Composing games into complex institutions.pdf` |
| A synthetic approach to Markov kernels (Fritz, arXiv 1908.07021), Markov categories, relevant for probabilistic/stochastic program semantics | `articles/Mathematics/Category Theory/1908.07021.pdf` |

## Tier 2: algebraic design as a programming practice (the practical payoff)

The "few core concepts + combinators + laws" playbook.

| Item | Location |
|---|---|
| Algebra-Driven Design (Maguire, 2020) | `articles/Computer Science/Functional Programming/Algebra Driven Design (Maguire) - 2020.pdf` |
| Denotational design with type class morphisms (Elliott) | `articles/Computer Science/Functional Programming/Denotational design with type class morphisms (extended version).pdf` |
| Denotational Design, from meanings to programs (Elliott, talk notes, 2 files) | `articles/Computer Science/Functional Programming/Denotational Design from meanings to programs Conal Elliott*.pdf` |
| Algebra of Programming (Bird, de Moor), split in two PDFs | `laptop_review/Algebra of Programming 1-3.pdf`, `laptop_review/Algebra of Programming 4-5.pdf` |
| Notes on "Algebra of Programming" | `articles/Computer Science/Functional Programming/Notes on “Algebra of Programming” .pdf` |
| Algebra of Programming using Dependent Types | `articles/Mathematics/Logic & Type Theory/dependent types/Algebra of Programming using Dependent Types.pdf` |
| Seven Sketches in Compositionality (Fong, Spivak, 2018), published as *An Invitation to Applied Category Theory* | `articles/Mathematics/Category Theory/Seven Sketches in Compositionality (Spivak) - 2018.pdf` |
| Category Theory for Programmers (Milewski, 2018), Scala edition too | `articles/Mathematics/Category Theory/Category Theory for programmers (Milewski) - 2018.pdf`, `...programmers-scala (Milewski) - 2018.pdf` |
| Data Types à la Carte (Swierstra, 2008) plus Compilation à la Carte and the compdata-param code | `articles/Computer Science/Functional Programming/Data Types a la Carte (Swierstra) - 2008.pdf`, `.../Compilation a la Carte.pdf`, `articles/Computer Science/compdata-param-master/` |
| Notions of computation and monads (Moggi, 1991) | `articles/Computer Science/Functional Programming/Notions of computation and monads (Moggi) - 1991.pdf` |
| Generalizing monads to arrows (Hughes, 2000) and the arrows-as-profunctors line | `articles/Computer Science/Functional Programming/Generalizing Monads to Arrows (Hughes) - 2000.pdf`, `articles/Mathematics/Category Theory/Categorifying Computations into Components via Arrows as Profunctors - 2010.pdf` |
| The essence of the iterator pattern (Gibbons) | `articles/Computer Science/Functional Programming/The essence of the iterator pattern (Gibbons).pdf` |
| Unifying Structured Recursion Schemes (Hinze, Wu) and Adjoint Folds and Unfolds | `articles/Computer Science/Functional Programming/Unifying Structured Recursion Schemes.pdf`, `.../Adjoint Folds and Unfolds Or- Scything Through the Thicket of Morphisms.pdf` |
| Functional programming with bananas, lenses, envelopes and barbed wire (Meijer et al) | `articles/Computer Science/Functional Programming/Functional programming with bananas, lenses, envelopes and barbed wire (Meijer).pdf` |
| Generic Programming with Adjunctions (Hinze) | `articles/Computer Science/Generic Programming/Generic Programming with Adjunctions.pdf` |
| Relational Algebra by Way of Adjunctions | `articles/Computer Science/Databases/Relational Algebra by Way of Adjunctions.pdf` |
| Folding DSLs: deep and shallow embeddings (Gibbons, 2013) | `articles/Computer Science/Programming Languages/Other/Folding Domain Specific Languages, Deep and Shallow Embeddings (Gibbons) - 2013.pdf` |
| Finally Tagless, Partially Evaluated (Carette, Kiselyov, Shan) | `articles/Computer Science/Functional Programming/Finally Tagless, Partially Evaluated*...pdf` (two copies) |
| Initial Algebra Semantics is Enough | `articles/Mathematics/Category Theory/Initial Algebra Semantics is Enough.pdf` |
| Universal Coalgebra, a theory of systems (Rutten, 2000) | `articles/Mathematics/Category Theory/Universal Coalgebra, a theory of systems (Rutten) - 2000.pdf` (copy in `laptop_review/`) |

## Tier 3: reference texts (look things up, read chapters as needed)

| Item | Location |
|---|---|
| Basic Category Theory (Leinster, 2016) | `articles/Mathematics/Category Theory/Basic Category Theory (Leinster) - 2016.pdf` |
| Category Theory in Context (Riehl, 2014) | `articles/Mathematics/Category Theory/Category Theory in Context (Riehl) - 2014.pdf` (plus screen edition) |
| Category Theory (Awodey, 2010) | `articles/Mathematics/Category Theory/Category Theory (Awodey) - 2010.pdf` |
| Categories for the Working Mathematician (Mac Lane) | `articles/Mathematics/Category Theory/Categories for the working mathematician (Mac Lane) - 1998.pdf` (3 copies) |
| Conceptual Mathematics (Lawvere, Schanuel) | `articles/Mathematics/Category Theory/Conceptual Mathematics (Lawvere) - 1997.pdf` |
| Notes on Category Theory (Perrone, 2019) | `articles/Mathematics/Category Theory/Notes on Category Theory (Perrone) - 2019.pdf` |
| Category Theory for the Sciences (Spivak, 2014) | `articles/Mathematics/Category Theory/Category Theory for the sciences (Spivak) - 2014.pdf` |
| Polynomial Functors / Poly book (Niu, Spivak) | `articles/Mathematics/Category Theory/poly-book.pdf`, plus `articles/Mathematics/Dynamical Systems/Poly, An abundant categorical setting for mode-dependent dynamics (Spivak) - 2020.pdf` and `Notes on Polynomial Functors (Kock) - 2016.pdf` |
| Categorical Systems Theory (Myers) | `articles/Mathematics/Category Theory/Categorical Systems Theory (Myers).pdf` |
| Category Theory Using String Diagrams (Marsden, 2014) | `articles/Mathematics/Diagrams & String Diagrams/Category Theory Using String Diagrams (Marsden) - 2014.pdf` |
| An Introduction to String Diagrams for Computer Scientists (arXiv 2305.08768) | `articles/Mathematics/Diagrams & String Diagrams/2305.08768.pdf` |
| A survey of graphical languages for monoidal categories (Selinger, 2009) | `articles/Mathematics/Diagrams & String Diagrams/A survey of graphical languages for monoidal categories  (Selinger) - 2009.pdf` |
| Coend Calculus (Loregian) | `articles/Mathematics/Category Theory/Coend Calculus (Loregian) - 2019.pdf` and `1501.02503.pdf` |
| Physics, Topology, Logic and Computation: a Rosetta Stone (Baez, Stay) | `articles/Mathematics/Category Theory/Physics, Topology, Logic and Computation - A Rosetta Stone (Baez) - 2009.pdf` |
| Monoidal Computer III (Pavlovic, 2018) | `articles/Computer Science/Theory of Computation/Monoidal computer III (Pavlovic)- 2018.pdf` |
| DisCoPy: monoidal categories in Python (arXiv 2005.02975), useful as an implementation reference | `articles/Mathematics/Category Theory/2005.02975.pdf` |
| What is Applied Category Theory (Bradley, 2018) | `articles/Mathematics/Category Theory/What is Applied Category Theory (Bradley) - 2018.pdf` |
| Own talk: Monoidal categories (SBTB 2020) | `SBTB2020/Monoidal categories-SBTB2020.pdf`, `talks/monoidal categories/` |

## The Para lineage (downloaded 2026-07-18 into `articles/Machine Learning/Categorical Deep Learning/`)

Originally gaps in the library, sourced from the
[bgavran/Category_Theory_Machine_Learning](https://github.com/bgavran/Category_Theory_Machine_Learning) list and adjacent work; now fetched from arXiv:

- **Fundamental Components of Deep Learning: A category-theoretic approach** (Gavranović PhD thesis, arXiv 2403.13001).
- **Backprop as Functor** (Fong, Spivak, Tuyéras, arXiv 1711.10455). The original learners paper.
- **Categorical Deep Learning: An Algebraic Theory of Architectures** (Gavranović, Lessard, Dudzik et al, arXiv 2402.15332).
- **Categorical Foundations of Gradient-Based Learning** (Cruttwell, Gavranović, Ghani, Wilson, Zanasi, arXiv 2103.01931). Closest single paper to the Para/optic story we are prototyping.
- **Towards Foundations of Categorical Cybernetics** (Capucci, Gavranović, Hedges, Rischel, arXiv 2105.06332). Para(Optic) as the general setting for agents and optimizers.
- **Category Theory in Machine Learning** (Shiebler, Gavranović, Wilson, arXiv 2106.07032). Survey, good map of the territory.
- **Lenses and Learners** (Fong, Johnson, arXiv 1903.03671).
- **CHAD: Combinatory Homomorphic Automatic Differentiation** (Vákár, Smeding, arXiv 2103.15776).

Second batch, found by actually reading the bgavran README rather than recalling it:

- **Operads for compositional reasoning in LLMs** (arXiv 2606.13634). Closest paper to dspy4s itself: compositional reasoning structure for LLM programs.
- **Towards a Categorical Foundation of Deep Learning: A Survey** (Petrache, Trager, arXiv 2410.05353). Newer and broader than the 2021 survey.
- **Learners' Languages** (Spivak, arXiv 2103.01189). Learners as polynomial functors and dynamical systems, connects to the Poly book.
- **From Open Learners to Open Games** (Hedges, arXiv 1902.08666). The learners/games equivalence.
- **General supervised learning as change propagation with delta lenses** (Diskin, arXiv 1911.12904).
- **Reverse Derivative Ascent** (Wilson, Zanasi, arXiv 2101.10488). Gradient-style optimization over boolean circuits, an example of the framework applied to a non-smooth setting (like ours).
- **Bayesian Updates Compose Optically** (Smithe, arXiv 2006.01631).
- **On the Anatomy of Attention** (Khatri, Laakkonen, Liu, Sadrzadeh, arXiv 2407.02423). Attention mechanisms in categorical terms.

Blog posts worth bookmarking (not downloaded):

- [Neural Networks, Types, and Functional Programming](https://colah.github.io/posts/2015-09-NN-Types-FP/) (Olah)
- [Towards Categorical Foundations of Learning](https://www.brunogavranovic.com/posts/2021-03-03-Towards-Categorical-Foundations-Of-Neural-Networks.html) (Gavranović)
- [Optics vs Lenses, Operationally](https://www.brunogavranovic.com/posts/2022-02-10-optics-vs-lenses-operationally.html) (Gavranović)
- [Meta-learning and Monads](https://www.brunogavranovic.com/posts/2021-10-13-meta-learning-and-monads.html) (Gavranović)
- [Generalized Transformers from Applicative Functors](https://glaive-research.org/2025/02/11/Generalized-Transformers-from-Applicative-Functors.html)

## Notes

- `articles/Mathematics/Category Theory/` holds ~140 items; the tiers above are the selection relevant to this push, not the full inventory.
- Related folders worth browsing later: `Mathematics/Diagrams & String Diagrams/`, `Mathematics/Logic & Type Theory/`, `Computer Science/Generic Programming/`, `Computer Science/Functional Programming/`.
- Undecided: copy the selection to this machine vs. read from studio over SSH/Dropbox.
