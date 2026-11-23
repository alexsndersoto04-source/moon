import heapq
from typing import Dict, List, Tuple


def dijkstra(graph: Dict[str, List[Tuple[str, float]]], start: str, goal: str) -> Tuple[float, List[str]]:
    queue: List[Tuple[float, str, List[str]]] = [(0.0, start, [start])]
    visited: Dict[str, float] = {}

    while queue:
        cost, node, path = heapq.heappop(queue)
        if node == goal:
            return cost, path
        if node in visited and visited[node] <= cost:
            continue
        visited[node] = cost
        for neighbor, weight in graph.get(node, []):
            new_cost = cost + weight
            if neighbor not in visited or new_cost < visited[neighbor]:
                heapq.heappush(queue, (new_cost, neighbor, path + [neighbor]))

    return float('inf'), []
