import unittest
from algorithms.pathfinding import dijkstra
from main import NETWORK


class TestAlgorithms(unittest.TestCase):
    def test_optimal_route(self):
        cost, route = dijkstra(NETWORK, 'Gateway', 'DB-Primary')
        self.assertEqual(cost, 7.0)
        self.assertEqual(route[0], 'Gateway')
        self.assertEqual(route[-1], 'DB-Primary')


if __name__ == '__main__':
    unittest.main(verbosity=2)
