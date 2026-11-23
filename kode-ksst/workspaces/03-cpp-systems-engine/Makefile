CXX := g++
CXXFLAGS := -std=c++20 -O2 -Wall -Wextra

all: build run

build:
	$(CXX) $(CXXFLAGS) src/main.cpp -o engine

run: build
	./engine

clean:
	rm -f engine
