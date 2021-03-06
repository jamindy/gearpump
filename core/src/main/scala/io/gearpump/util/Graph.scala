/*
 * Licensed under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.gearpump.util

import scala.collection.immutable.Stack
import scala.collection.mutable
import scala.language.implicitConversions
import scala.util.{Failure, Success, Try}

/**
 * Generic mutable Graph libraries.
 */
class Graph[N, E](vertexList: List[N], edgeList: List[(N, E, N)]) extends Serializable {
  private val LOG = LogUtil.getLogger(getClass)
  private val vertices = mutable.Set.empty[N]
  private val edges = mutable.Set.empty[(N, E, N)]
  private val outEdges = mutable.Map.empty[N, mutable.Set[(N, E, N)]]
  private val inEdges = mutable.Map.empty[N, mutable.Set[(N, E, N)]]

  // This is used to ensure the output of this Graph is always stable
  // Like method getVertices(), or getEdges()
  private var indexs = Map.empty[Any, Int]
  private var nextIndex = 0
  private def nextId: Int = {
    val result = nextIndex
    nextIndex += 1
    result
  }

  private def init(): Unit = {
    Option(vertexList).getOrElse(List.empty[N]).foreach(addVertex)
    Option(edgeList).getOrElse(List.empty[(N, E, N)]).foreach(addEdge)
  }

  init()

  /**
   * Add a vertex
   * Current Graph is changed.
   */
  def addVertex(vertex: N): Unit = {
    val result = vertices.add(vertex)
    if (result) {
      indexs += vertex -> nextId
    }
  }

  /**
   * Add an edge
   * Current Graph is changed.
   */
  def addEdge(edge: (N, E, N)): Unit = {
    val result = edges.add(edge)
    if (result) {
      indexs += edge -> nextId
      outEdges += edge._1 -> (outgoingEdgesOf(edge._1) + edge)
      inEdges += edge._3 -> (incomingEdgesOf(edge._3) + edge)
    }
  }

  /**
   * return all vertices.
   * The result is stable
   */
  def getVertices: List[N] = {
    // Sorts the vertex so that we can keep the order for mapVertex
    vertices.toList.sortBy(indexs(_))
  }

  /**
   * out degree
   */
  def outDegreeOf(node: N): Int = {
    outgoingEdgesOf(node).size
  }

  /**
   * in degree
   */
  def inDegreeOf(node: N): Int = {
    incomingEdgesOf(node).size
  }

  /**
   * out going edges.
   */
  def outgoingEdgesOf(node: N): mutable.Set[(N, E, N)] = {
    outEdges.getOrElse(node, mutable.Set.empty)
  }

  /**
   * incoming edges.
   */
  def incomingEdgesOf(node: N): mutable.Set[(N, E, N)] = {
    inEdges.getOrElse(node, mutable.Set.empty)
  }

  /**
   * adjacent vertices.
   */
  private def adjacentVertices(node: N): List[N] = {
    outgoingEdgesOf(node).map(_._3).toList
  }

  /**
   * Remove vertex
   * Current Graph is changed.
   */
  def removeVertex(node: N): Unit = {
    vertices.remove(node)
    indexs -= node
    val toBeRemoved = incomingEdgesOf(node) ++ outgoingEdgesOf(node)
    toBeRemoved.foreach(removeEdge)
    outEdges -= node
    inEdges -= node
  }

  /**
   * Remove edge
   * Current Graph is changed.
   */
  private def removeEdge(edge: (N, E, N)): Unit = {
    indexs -= edge
    edges.remove(edge)
    inEdges.update(edge._3, inEdges(edge._3) - edge)
    outEdges.update(edge._1, outEdges(edge._1) - edge)
  }

  /**
   * add edge
   * Current Graph is changed.
   */
  def addVertexAndEdge(node1: N, edge: E, node2: N): Unit = {
    addVertex(node1)
    addVertex(node2)
    addEdge((node1, edge, node2))
  }

  /**
   * Map a graph to a new graph, with vertex converted to a new type
   * Current Graph is not changed.
   */
  def mapVertex[NewNode](fun: N => NewNode): Graph[NewNode, E] = {
    val newVertices = getVertices.map(node => (node, fun(node)))

    val vertexMap: Map[N, NewNode] = newVertices.toMap

    val newEdges = getEdges.map { edge =>
      (vertexMap(edge._1), edge._2, vertexMap(edge._3))
    }
    new Graph(newVertices.map(_._2), newEdges)
  }

  /**
   * Map a graph to a new graph, with edge converted to new type
   * Current graph is not changed.
   */
  def mapEdge[NewEdge](fun: (N, E, N) => NewEdge): Graph[N, NewEdge] = {
    val newEdges = getEdges.map { edge =>
      (edge._1, fun(edge._1, edge._2, edge._3), edge._3)
    }
    new Graph(getVertices, newEdges)
  }

  /**
   * edges connected to node
   */
  def edgesOf(node: N): List[(N, E, N)] = {
    (incomingEdgesOf(node) ++ outgoingEdgesOf(node)).toList
  }

  /**
   * all edges
   */
  def getEdges: List[(N, E, N)] = {
    // Sorts the edges so that we can keep the order for mapEdges
    edges.toList.sortBy(indexs(_))
  }

  /**
   * Add another graph
   * Current graph is changed.
   */
  def addGraph(other: Graph[N, E]): Graph[N, E] = {
    (getVertices ++ other.getVertices).foreach(addVertex)
    (getEdges ++ other.getEdges).foreach(edge => addVertexAndEdge(edge._1, edge._2, edge._3))
    this
  }

  /**
   * clone the graph
   */
  def copy: Graph[N, E] = {
    new Graph(getVertices, getEdges)
  }

  /**
   * check empty
   */
  def isEmpty: Boolean = {
    val vertexCount = getVertices.size
    val edgeCount = getEdges.length
    if (vertexCount + edgeCount == 0) {
      true
    } else {
      false
    }
  }

  /**
   * sub-graph which contains current node and all neighbour
   * nodes and edges.
   */
  def subGraph(node: N): Graph[N, E] = {
    val newGraph = Graph.empty[N, E]
    for (edge <- edgesOf(node)) {
      newGraph.addVertexAndEdge(edge._1, edge._2, edge._3)
    }
    newGraph
  }

  /**
   * replace vertex, the current Graph is mutated.
   */
  def replaceVertex(node: N, newNode: N): Graph[N, E] = {
    for (edge <- incomingEdgesOf(node)) {
      addVertexAndEdge(edge._1, edge._2, newNode)
    }

    for (edge <- outgoingEdgesOf(node)) {
      addVertexAndEdge(newNode, edge._2, edge._3)
    }
    removeVertex(node)
    this
  }

  private def removeZeroInDegree: List[N] = {
    val toBeRemoved = getVertices.filter(inDegreeOf(_) == 0)
    toBeRemoved.foreach(removeVertex)
    toBeRemoved
  }

  /**
   * Return an iterator of vertex in topological order
   * The node returned by Iterator is stable sorted.
   */
  def topologicalOrderIterator: Iterator[N] = {
    tryTopologicalOrderIterator match {
      case Success(iterator) => iterator
      case Failure(_) =>
        LOG.warn("Please note this graph is cyclic.")
        topologicalOrderWithCyclesIterator
    }
  }

  private def tryTopologicalOrderIterator: Try[Iterator[N]] = {
    Try {
      var indegreeMap = getVertices.map(v => v -> inDegreeOf(v)).toMap

      val verticesWithZeroIndegree = mutable.Queue(indegreeMap.filter(_._2 == 0).keys
        .toList.sortBy(indexs(_)): _*)
      var output = List.empty[N]
      var count = 0
      while (verticesWithZeroIndegree.nonEmpty) {
        val vertex = verticesWithZeroIndegree.dequeue()
        adjacentVertices(vertex).foreach { adjacentV =>
          indegreeMap += adjacentV -> (indegreeMap(adjacentV) - 1)
          if (indegreeMap(adjacentV) == 0) {
            verticesWithZeroIndegree.enqueue(adjacentV)
          }
        }
        output :+= vertex
        count += 1
      }
      if (count != getVertices.size) {
        throw new Exception("There exists a cycle in the graph")
      }
      output.iterator
    }
  }

  /**
   * Find all cycles in graph including one-node cycle and
   * return an inverted index from a node to its containing cycle.
   */
  private def findCycles: Map[N, List[N]] = {
    var visited = Set.empty[N]
    var stack = Stack[N]()
    var indexMap = Map.empty[N, Int]
    var cycleRoot = Map.empty[N, Int]
    var index = 0

    var nodeToCycles = Map.empty[N, List[N]]

    def visit(node: N): Unit = {
      indexMap += node -> index
      cycleRoot += node -> index
      index += 1
      visited += node
      stack = stack.push(node)

      outgoingEdgesOf(node).foreach {
        case (_, _, successor) => {
          if (!visited(successor)) { // depth-first traverse
            visit(successor)
            if (cycleRoot(successor) < cycleRoot(node)) {
              cycleRoot += node -> cycleRoot(successor)
            }
          } else if (indexMap(successor) < cycleRoot(node)) {
            cycleRoot += node -> indexMap(successor)
          }
        }
      }

      if (stack.nonEmpty) {
        val hr = cycleRoot(stack.head)
        val cycle = mutable.MutableList.empty[N]
        do {
          cycle += stack.head
          stack = stack.pop
        } while (stack.nonEmpty && hr <= indexMap(stack.head))
        cycle.foreach { node =>
          nodeToCycles += node -> cycle.toList
        }
      }
    }

    getVertices.collect {
      case node if !visited(node) => {
        visit(node)
      }
    }

    nodeToCycles
  }

  /**
   * Return an iterator of vertex in topological order of graph with circles
   * The node returned by Iterator is stable sorted.
   *
   * The reference of this algorithm is:
   * http://www.drdobbs.com/database/topological-sorting/184410262
   */
  def topologicalOrderWithCyclesIterator: Iterator[N] = {
    val topo = getAcyclicCopy().topologicalOrderIterator
    topo.flatMap(_.sortBy(indexs(_)).iterator)
  }

  private def getAcyclicCopy(): Graph[List[N], E] = {
    val nodeToCycles = findCycles
    val newGraph = Graph.empty[List[N], E]
    var added = Set.empty[(List[N], List[N])]
    getEdges.foreach { case (from, via, to) =>
      val fc = nodeToCycles(from)
      val tc = nodeToCycles(to)
      if (fc != tc && !added(fc -> tc) && !added(tc -> fc)) {
        added += fc -> tc
        newGraph.addVertexAndEdge(fc, via, tc)
      }
    }
    newGraph
  }

  /**
   * check whether there is a loop
   */
  def hasCycle(): Boolean = {
    tryTopologicalOrderIterator.isFailure
  }

  /**
   * Check whether there are two edges connecting two nodes.
   */
  def hasDuplicatedEdge(): Boolean = {
    getEdges.groupBy(edge => (edge._1, edge._3)).values.exists(_.size > 1)
  }

  /**
   * Generate a level map for each vertex withholding:
   * {{{
   * if vertex A -> B, then level(A) -> level(B)
   * }}}
   */
  def vertexHierarchyLevelMap(): Map[N, Int] = {
    val newGraph = getAcyclicCopy()
    var output = Map.empty[N, Int]
    var level = 0
    while (!newGraph.isEmpty) {
      val toBeRemovedLists = newGraph.removeZeroInDegree
      val maxLength = toBeRemovedLists.map(_.length).max
      for (subGraph <- toBeRemovedLists) {
        val sorted = subGraph.sortBy(indexs)
        for (i <- sorted.indices) {
          output += sorted(i) -> (level + i)
        }
      }
      level += maxLength
    }
    output
  }

  override def toString: String = {
    Map("vertices" -> getVertices.mkString(","),
      "edges" -> getEdges.mkString(",")).toString()
  }
}

object Graph {

  /**
   * Example:
   *
   * {{{
   * Graph(1 ~ 2 ~> 4 ~ 5 ~> 7, 8~9~>55, 11)
   * Will create a graph with:
   * nodes:
   * 1, 4, 7, 8, 55, 11
   * edge:
   * 2: (1->4)
   * 5: (4->7)
   * 9: (8->55)
   * }}}
   */
  def apply[N, E](elems: Path[_ <: N, _ <: E]*): Graph[N, E] = {
    val graph = empty[N, E]
    elems.foreach { path =>
      path.updategraph(graph)
    }
    graph
  }

  def apply[N, E](vertices: List[N], edges: List[(N, E, N)]): Graph[N, E] = {
    new Graph(vertices, edges)
  }

  def unapply[N, E](graph: Graph[N, E]): Option[(List[N], List[(N, E, N)])] = {
    Some((graph.getVertices, graph.getEdges))
  }

  def empty[N, E]: Graph[N, E] = {
    new Graph(List.empty[N], List.empty[(N, E, N)])
  }

  class Path[N, + E](path: List[Either[N, E]]) {

    def ~[Edge >: E](edge: Edge): Path[N, Edge] = {
      new Path(path :+ Right(edge))
    }

    def ~>[NodeT >: N](node: NodeT): Path[NodeT, E] = {
      new Path(path :+ Left(node))
    }

    def to[NodeT >: N, EdgeT >: E](node: NodeT, edge: EdgeT): Path[NodeT, EdgeT] = {
      this ~ edge ~> node
    }

    private[Graph] def updategraph[NodeT >: N, EdgeT >: E](graph: Graph[NodeT, EdgeT]): Unit = {
      val nodeEdgePair: Tuple2[Option[N], Option[E]] = (None, None)
      path.foldLeft(nodeEdgePair) { (pair, either) =>
        val (lastNode, lastEdge) = pair
        either match {
          case Left(node) =>
            graph.addVertex(node)
            if (lastNode.isDefined) {
              graph.addVertexAndEdge(
                lastNode.get, lastEdge.getOrElse(null.asInstanceOf[EdgeT]), node)
            }
            (Some(node), None)
          case Right(edge) =>
            (lastNode, Some(edge))
        }
      }
    }
  }

  object Path {
    implicit def anyToPath[N, E](any: N): Path[N, E] = Node(any)
  }

  implicit class Node[N, E](self: N) extends Path[N, E](List(Left(self))) {

    override def ~[EdgeT](edge: EdgeT): Path[N, EdgeT] = {
      new Path(List(Left(self), Right(edge)))
    }

    override def ~>[NodeT >: N](node: NodeT): Path[NodeT, E] = {
      new NodeList(List(self, node))
    }

    override def to[NodeT >: N, EdgeT >: E](node: NodeT, edge: EdgeT): Path[NodeT, EdgeT] = {
      this ~ edge ~> node
    }
  }

  class NodeList[N, E](nodes: List[N]) extends Path[N, E](nodes.map(Left(_))) {
    override def ~[EdgeT](edge: EdgeT): Path[N, EdgeT] = {
      new Path(nodes.map(Left(_)) :+ Right(edge))
    }

    override def ~>[NodeT >: N](node: NodeT): Path[NodeT, E] = {
      new NodeList(nodes :+ node)
    }

    override def to[NodeT >: N, EdgeT >: E](node: NodeT, edge: EdgeT): Path[NodeT, EdgeT] = {
      this ~ edge ~> node
    }
  }
}