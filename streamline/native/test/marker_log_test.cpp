#include "internal/state.h"

#include "doctest.h"

#include <cstdint>
#include <vector>

using namespace mc_dlss;

namespace {

struct FakeToken : sl::FrameToken {
	uint32_t index;
	explicit FakeToken(uint32_t i) : index(i) {}
	operator uint32_t() const override { return index; }
};

void require_reads_empty(const MarkerLog& log) {
	uint32_t counts[kMarkerTypeCapacity] = {};
	uint32_t eventCount = 0;
	uint32_t events[kMarkerLogSize * 2] = {};
	CHECK(log.read(counts, kMarkerTypeCapacity, &eventCount, events, kMarkerLogSize) ==
		  kNotInitialized);
	CHECK(eventCount == 0);
}

}

TEST_CASE("MarkerLog read refuses before any record (kNotInitialized)") {
	const MarkerLog log;
	require_reads_empty(log);
}

TEST_CASE("MarkerLog read rejects null out-pointers (kInvalidParameter)") {
	MarkerLog log;
	FakeToken token(7);
	log.record(kReflexMarkerInputSample, &token);
	REQUIRE(log.eventCount == 1);

	uint32_t counts[kMarkerTypeCapacity] = {};
	uint32_t eventCount = 0;
	uint32_t events[kMarkerLogSize * 2] = {};
	CHECK(log.read(nullptr, kMarkerTypeCapacity, &eventCount, events, kMarkerLogSize) ==
		  kInvalidParameter);
	CHECK(log.read(counts, kMarkerTypeCapacity, nullptr, events, kMarkerLogSize) ==
		  kInvalidParameter);
	CHECK(log.read(counts, kMarkerTypeCapacity, &eventCount, nullptr, kMarkerLogSize) ==
		  kInvalidParameter);
	CHECK(eventCount == 0);
}

TEST_CASE("MarkerLog records cumulative per-type counts and ordered events") {
	MarkerLog log;
	FakeToken t10(10), t11(11), t12(12), t13(13);
	log.record(kReflexMarkerSimulationStart, &t10);
	log.record(kReflexMarkerInputSample, &t11);
	log.record(kReflexMarkerSimulationEnd, &t12);
	log.record(kReflexMarkerSimulationStart, &t13);

	uint32_t counts[kMarkerTypeCapacity] = {};
	uint32_t eventCount = 0;
	uint32_t events[kMarkerLogSize * 2] = {};
	REQUIRE(log.read(counts, kMarkerTypeCapacity, &eventCount, events, kMarkerLogSize) ==
			kSuccess);
	CHECK(counts[kReflexMarkerInputSample] == 1);
	CHECK(counts[kReflexMarkerSimulationStart] == 2);
	CHECK(counts[kReflexMarkerSimulationEnd] == 1);
	CHECK(counts[kReflexMarkerRenderSubmitStart] == 0);
	CHECK(counts[kReflexMarkerRenderSubmitEnd] == 0);
	CHECK(eventCount == 4);
	const uint32_t expected[kMarkerLogSize * 2] = {
		1, 10, 0, 11, 2, 12, 1, 13,
	};
	for (uint32_t i = 0; i < 8; ++i) {
		CHECK(events[i] == expected[i]);
	}
}

TEST_CASE("MarkerLog read copies at most the caller's capacities") {
	MarkerLog log;
	FakeToken t0(0), t1(1), t2(2);
	log.record(kReflexMarkerInputSample, &t0);
	log.record(kReflexMarkerSimulationStart, &t1);
	log.record(kReflexMarkerInputSample, &t2);

	uint32_t counts[kMarkerTypeCapacity + 2] = {};
	uint32_t eventCount = 0;
	uint32_t events[4] = {};
	REQUIRE(log.read(counts, kMarkerTypeCapacity + 2, &eventCount, events, 2) == kSuccess);
	CHECK(counts[kReflexMarkerInputSample] == 2);
	CHECK(counts[kReflexMarkerSimulationStart] == 1);
	CHECK(counts[kReflexMarkerSimulationEnd] == 0);
	CHECK(eventCount == 3);
	CHECK(events[0] == kReflexMarkerInputSample);
	CHECK(events[1] == 0);
	CHECK(events[2] == kReflexMarkerSimulationStart);
	CHECK(events[3] == 1);
}

TEST_CASE("MarkerLog drops out-of-vocabulary types without writing") {
	MarkerLog log;
	FakeToken token(5);
	log.record(kMarkerTypeCapacity, &token);
	log.record(kMarkerTypeCapacity + 100, &token);
	require_reads_empty(log);
}

TEST_CASE("MarkerLog ring wraps and keeps the newest 16, oldest first") {
	MarkerLog log;
	std::vector<FakeToken> tokens;
	tokens.reserve(20);
	for (uint32_t i = 0; i < 20; ++i) {
		tokens.emplace_back(i);
		log.record(i % 2, &tokens[i]);
	}

	uint32_t counts[kMarkerTypeCapacity] = {};
	uint32_t eventCount = 0;
	uint32_t events[kMarkerLogSize * 2] = {};
	REQUIRE(log.read(counts, kMarkerTypeCapacity, &eventCount, events, kMarkerLogSize) ==
			kSuccess);
	CHECK(eventCount == 20);
	CHECK(counts[0] == 10);
	CHECK(counts[1] == 10);
	CHECK(counts[2] == 0);
	for (uint32_t i = 0; i < kMarkerLogSize; ++i) {
		const uint32_t emitted = 4 + i;
		CHECK(events[i * 2] == emitted % 2);
		CHECK(events[i * 2 + 1] == emitted);
	}
}
