from algorithms.pathfinding import dijkstra

NETWORK = {
    'Gateway': [('Auth', 2), ('CDN', 5)],
    'Auth': [('API-Core', 3), ('Cache', 1)],
    'CDN': [('API-Core', 1)],
    'Cache': [('DB-Primary', 4)],
    'API-Core': [('Worker', 2), ('DB-Primary', 6)],
    'Worker': [('DB-Primary', 1)],
    'DB-Primary': [],
}

if __name__ == '__main__':
    cost, route = dijkstra(NETWORK, 'Gateway', 'DB-Primary')
    print(f"Optimal latency route: {' -> '.join(route)}")
    print(f"Total latency: {cost:.1f} ms")
