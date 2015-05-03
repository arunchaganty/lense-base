package edu.stanford.lense_base

import edu.stanford.lense_base.gameplaying.{LookaheadOneHeuristic, GamePlayer}
import edu.stanford.lense_base.graph.{GraphNode, GraphStream, Graph}
import edu.stanford.lense_base.server.{MulticlassQuestion, WorkUnitServlet, WorkUnit}

import scala.collection.mutable
import scala.concurrent.Promise
import scala.util.{Random, Try}

/**
 * Created by keenon on 5/2/15.
 *
 * This holds the abstract guts of a use case for Lense, which should help minimize crufty overhead when generating new
 * use cases. You can of course still use LenseEngine directly, but this might be a time-saver in general
 */
abstract class LenseUseCase[Input <: AnyRef, Output <: AnyRef] {

  val graphStream : GraphStream = new GraphStream()
  val lenseEngine : LenseEngine = new LenseEngine(graphStream, gamePlayer)

  // Add the training data as a list of labeled graphs
  lenseEngine.addTrainingData(initialTrainingData.map(pair => toLabeledGraph(pair._1, pair._2)))

  /**
   * This function takes an Input
   * This must return a graph created in the local GraphStream, and it should create a GraphNodeQuestion for each node
   * in the created graph, in case we want to query humans about it.
   *
   * @param input the input that the graph will represent
   * @return a graph representing the input, and taking labels from the output if it is passed in
   */
  def toGraphAndQuestions(input : Input) : (Graph, Map[GraphNode, GraphNodeQuestion])

  /**
   * Returns the correct labels for all the nodes in the graph, given a graph and the corresponding gold output. This
   * is used both for generating initial training data and for doing analysis during testing
   *
   * @param graph the graph we need to attach labels to
   * @param goldOutput the output we expect from the graph labellings
   * @return
   */
  def toGoldGraphLabels(graph : Graph, goldOutput : Output) : Map[GraphNode, String]

  /**
   * Reads the MAP assignment out of the values object, and returns an Output corresponding to this graph having these
   * values.
   * The keys of the values map will always correspond one-to-one with the nodes of the graph.
   *
   * @param graph the graph, with observedValue's on all the nodes
   * @param values a map corresponding the nodes of the graph with their String labels
   * @return an Output version of this graph
   */
  def toOutput(graph : Graph, values : Map[GraphNode, String]) : Output

  /**
   * A way to define the loss function for you system. mostLikelyGuesses is a list of all the nodes being chosen on,
   * with their corresponding most likely label, and the probability the model assigns to the label.
   *
   * TODO: more docs here
   *
   * @param mostLikelyGuesses
   * @param cost
   * @param time
   * @return
   */
  def lossFunction(mostLikelyGuesses : List[(GraphNode,String,Double)], cost : Double, time : Double) : Double

  /**
   * An opportunity to provide some seed data for training the model before the online task begins. This data will
   * be used to train the classifier prior to any testing or online activity
   *
   * @return model seed training data
   */
  def initialTrainingData : List[(Input, Output)] = List()

  /**
   * An opportunity to provide a new game player, besides the default
   *
   * @return a game player
   */
  def gamePlayer : GamePlayer = LookaheadOneHeuristic

  ////////////////////////////////////////////////
  //
  //  These are functions that LenseUseCase provides, assuming the above are correct
  //
  ////////////////////////////////////////////////

  /**
   * This will run a test against artificial humans, with a "probability epsilon choose uniformly at random" error
   * function. It will print results to stdout.
   *
   * @param goldPairs pairs of Input and the corresponding correct Output objects
   * @param humanErrorRate the error rate epsilon, so if 0.3, then with P=0.3 artificial humans will choose uniformly at random
   */
  def testWithArtificialHumans(goldPairs : List[(Input, Output)], humanErrorRate : Double) : Unit = {
    val rand = new Random()
    analyzeOutput(goldPairs.map(pair => {
      val graph = toGraphAndQuestions(pair._1)._1
      val goldMap = toGoldGraphLabels(graph, pair._2)
      val guessMap = classifyWithArtificialHumans(graph, pair._2, humanErrorRate, rand)
      (graph, goldMap, guessMap)
    }))
  }

  /**
   * This will run a test against real live humans. The LenseUseCase needs to be triggered from inside Jetty, once it's
   * running, so that there's a server to ask for human opinions.
   *
   * @param goldPairs pairs of Input and the corresponding correct Output objects
   */
  def testWithRealHumans(goldPairs : List[(Input, Output)]) : Unit = {
    analyzeOutput(goldPairs.map(pair => {
      val graphAndQuestions = toGraphAndQuestions(pair._1)
      val goldMap = toGoldGraphLabels(graphAndQuestions._1, pair._2)
      val guessMap = classifyWithRealHumans(graphAndQuestions._1, graphAndQuestions._2)
      (graphAndQuestions._1, goldMap, guessMap)
    }))
  }

  /**
   * Transforms an input to the desired output, using real humans to collect extra information.
   *
   * @param input the input to be transformed
   * @return the desired output
   */
  def getOutput(input : Input) : Output = {
    val graphAndQuestion = toGraphAndQuestions(input)
    toOutput(graphAndQuestion._1, classifyWithRealHumans(graphAndQuestion._1, graphAndQuestion._2))
  }

  // Prints some outputs to stdout that are the result of analysis
  private def analyzeOutput(l : List[(Graph, Map[GraphNode, String], Map[GraphNode, String])]) : Unit = {
    val confusion : mutable.Map[(String,String), Int] = mutable.Map()
    var correct = 0.0
    var incorrect = 0.0
    for (triple <- l) {
      for (node <- triple._1) {
        val trueValue = triple._2(node)
        val guessedValue = triple._3(node)
        if (trueValue == guessedValue) correct += 1
        else incorrect += 1
        confusion.put((trueValue, guessedValue), confusion.getOrElse((trueValue, guessedValue), 0) + 1)
      }
    }
    println("Accuracy: "+(correct/(correct+incorrect)))
    println("Confusion: "+confusion)
  }

  private def classifyWithRealHumans(graph : Graph, humanQuestionMap : Map[GraphNode, GraphNodeQuestion]) : Map[GraphNode, String] = {
    lenseEngine.predict(graph, (n) => humanQuestionMap(n).getHumanOpinion, lossFunction)
  }

  private def classifyWithArtificialHumans(graph : Graph, output : Output, humanErrorRate : Double, rand : Random) : Map[GraphNode, String] = {
    val correctLabels = toGoldGraphLabels(graph, output)
    lenseEngine.predict(graph, (n) => {
      val correct = correctLabels(n)
      // Do the automatic error generation
      val guess = if (rand.nextDouble() > humanErrorRate) {
        correct
      }
      else {
        // Pick uniformly at random
        n.nodeType.possibleValues.toList(rand.nextInt(n.nodeType.possibleValues.size))
      }

      val promise = Promise[String]()
      promise.complete(Try { guess })
      promise
    }, lossFunction)
  }

  private def toLabeledGraph(input : Input, output : Output) : Graph = {
    val graph = toGraphAndQuestions(input)._1
    val labels = toGoldGraphLabels(graph, output)
    for (node <- graph.nodes) {
      node.observedValue = labels(node)
    }
    graph
  }
}

case class GraphNodeAnswer(displayText : String, internalClassName : String)
case class GraphNodeQuestion(questionToDisplay : String, possibleAnswers : List[GraphNodeAnswer]) {
  def getHumanOpinion : Promise[String] = {
    val p = Promise[String]()
    WorkUnitServlet.addWorkUnit(
      new MulticlassQuestion(
        questionToDisplay,
        possibleAnswers.map(possibleAnswer => (possibleAnswer.displayText, possibleAnswer.internalClassName)),
        p
      )
    )
    p
  }
}
