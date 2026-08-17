// The frame present-eligibility state machine, exercised device-free.
//
// These rules used to be a 28-line comment on nine flat DlssState fields, enforced by hand at
// eight call sites plus one free invalidate helper - reachable only through the full ABI with a
// live Vulkan device. As a type they are decidable here: arming under equal and unequal frame
// indexes, handoff consumption, a partial re-tag never reviving a consumed set, the present
// bracket, the once-per-frame orientation-copy guard, and invalidation.
//
// Nothing here touches a Vulkan or Streamline object: the frame token is only ever a non-null
// pointer nothing dereferences.
#include "internal/state.h"

#include "doctest.h"

#include <cstdint>

using namespace mc_dlss;

namespace {

sl::FrameToken* fake_token(const std::uintptr_t value = 1) {
	return reinterpret_cast<sl::FrameToken*>(value);
}

// A frame that tagged both sides under one index, with its token retained - the state a
// present handoff accepts.
FrameEligibility composed_frame(const uint32_t frameIndex) {
	FrameEligibility eligibility;
	eligibility.tokenSlot() = fake_token();
	eligibility.armSr(frameIndex);
	eligibility.armFg(frameIndex);
	return eligibility;
}

} // namespace

TEST_CASE("a fresh frame is eligible for nothing") {
	const FrameEligibility eligibility;
	CHECK_FALSE(eligibility.hasToken());
	CHECK_FALSE(eligibility.srArmed());
	CHECK_FALSE(eligibility.fgArmed());
	CHECK_FALSE(eligibility.tagSetComplete());
	CHECK_FALSE(eligibility.handoffEligible());
	CHECK_FALSE(eligibility.presentArmed());
	CHECK_FALSE(eligibility.presentStartPending());
	// No copies were ever recorded, so every frame index still needs its blits.
	CHECK(eligibility.copiesNeededFor(0));
}

TEST_CASE("a handoff needs both sides under one index and the token they recorded under") {
	// One side alone is never a set.
	FrameEligibility srOnly;
	srOnly.tokenSlot() = fake_token();
	srOnly.armSr(7);
	CHECK_FALSE(srOnly.tagSetComplete());
	CHECK_FALSE(srOnly.handoffEligible());

	// Two sides under different indexes are two frames' half-records, not one frame's set.
	FrameEligibility mismatched;
	mismatched.tokenSlot() = fake_token();
	mismatched.armSr(7);
	mismatched.armFg(8);
	CHECK_FALSE(mismatched.tagSetComplete());
	CHECK_FALSE(mismatched.handoffEligible());

	// A complete set whose token was released is still a complete set - the two refusals stay
	// distinguishable, which is what lets the ABI answer kInvalidParameter for one and
	// kNotInitialized for the other.
	FrameEligibility tokenless = composed_frame(7);
	tokenless.releaseToken();
	CHECK(tokenless.tagSetComplete());
	CHECK_FALSE(tokenless.handoffEligible());

	CHECK(composed_frame(7).handoffEligible());
}

TEST_CASE("the handoff consumes the tag set and keeps the token for the present bracket") {
	FrameEligibility eligibility = composed_frame(7);
	eligibility.consumeForHandoff();

	// Both sides cleared, so a second handoff for the same set refuses.
	CHECK_FALSE(eligibility.srArmed());
	CHECK_FALSE(eligibility.fgArmed());
	CHECK_FALSE(eligibility.handoffEligible());
	// The token survives: the bracket's markers are emitted around the actual queue present.
	CHECK(eligibility.hasToken());
	CHECK(eligibility.presentArmed());
	CHECK(eligibility.presentStartPending());
}

TEST_CASE("a partial re-tag never revives a consumed handoff") {
	FrameEligibility eligibility = composed_frame(7);
	eligibility.consumeForHandoff();

	// Repeating only the SR side re-arms only that side.
	eligibility.armSr(7);
	CHECK(eligibility.srArmed());
	CHECK_FALSE(eligibility.tagSetComplete());
	CHECK_FALSE(eligibility.handoffEligible());

	// The set stays refused until the counterpart records too.
	eligibility.armFg(7);
	CHECK(eligibility.handoffEligible());
}

TEST_CASE("the present bracket opens once and consumes the frame") {
	FrameEligibility eligibility = composed_frame(7);
	eligibility.consumeForHandoff();

	CHECK(eligibility.presentStartPending());
	eligibility.markPresentStartEmitted();
	// An already-open bracket is not a second START: a present that threw between the markers
	// must not corrupt the correlation.
	CHECK_FALSE(eligibility.presentStartPending());
	CHECK(eligibility.presentStartEmitted());

	eligibility.consumePresent();
	CHECK_FALSE(eligibility.presentArmed());
	CHECK_FALSE(eligibility.presentStartEmitted());
	CHECK_FALSE(eligibility.hasToken());
	CHECK_FALSE(eligibility.srArmed());
	CHECK_FALSE(eligibility.fgArmed());
}

TEST_CASE("an armed bracket whose START never emitted is consumed like a successful one") {
	FrameEligibility eligibility = composed_frame(7);
	eligibility.consumeForHandoff();
	// The START's slPCLSetMarker failed, so nothing marked it emitted. The frame presented
	// either way, so nothing may be left for a later present to open.
	eligibility.consumePresent();
	CHECK_FALSE(eligibility.presentArmed());
	CHECK_FALSE(eligibility.presentStartPending());
}

TEST_CASE("an unarmed present has no bracket to open") {
	FrameEligibility eligibility;
	eligibility.tokenSlot() = fake_token();
	eligibility.armSr(7);
	// An SR-only frame never handed off, so the present seam finds nothing armed.
	CHECK_FALSE(eligibility.presentStartPending());
}

TEST_CASE("the orientation copies are recorded once per frame index") {
	FrameEligibility eligibility;
	CHECK(eligibility.copiesNeededFor(7));
	eligibility.recordCopies(7);
	// The frame's second FG tag call must not rewrite copies its first already declared.
	CHECK_FALSE(eligibility.copiesNeededFor(7));
	// The retained token advances per frame, so a fresh index means copies to rebuild.
	CHECK(eligibility.copiesNeededFor(8));
}

TEST_CASE("srArmedAt tells the frame's two FG tag calls apart") {
	FrameEligibility eligibility;
	eligibility.tokenSlot() = fake_token();
	// The frame's first FG tag call runs before the SR tag: nothing recorded under this index.
	CHECK_FALSE(eligibility.srArmedAt(7));
	eligibility.armSr(7);
	CHECK(eligibility.srArmedAt(7));
	// A record from an earlier frame is not this frame's SR tag.
	CHECK_FALSE(eligibility.srArmedAt(8));
}

TEST_CASE("the index oracle refuses until both sides recorded") {
	FrameEligibility eligibility;
	uint32_t srFrameIndex = 0xFFFFFFFF;
	uint32_t fgFrameIndex = 0xFFFFFFFF;
	CHECK_FALSE(eligibility.tagIndexes(&srFrameIndex, &fgFrameIndex));
	eligibility.armSr(7);
	// Equality of one recorded slot and one never-recorded slot is meaningless.
	CHECK_FALSE(eligibility.tagIndexes(&srFrameIndex, &fgFrameIndex));
	// The out-parameters are untouched by a refusal.
	CHECK(srFrameIndex == 0xFFFFFFFF);
	CHECK(fgFrameIndex == 0xFFFFFFFF);

	eligibility.armFg(7);
	CHECK(eligibility.tagIndexes(&srFrameIndex, &fgFrameIndex));
	CHECK(srFrameIndex == 7);
	CHECK(fgFrameIndex == 7);
}

TEST_CASE("invalidate drops the whole in-flight frame") {
	FrameEligibility eligibility = composed_frame(7);
	eligibility.consumeForHandoff();
	eligibility.markPresentStartEmitted();
	eligibility.recordCopies(7);
	eligibility.armSr(7);
	eligibility.armFg(7);

	eligibility.invalidate();

	CHECK_FALSE(eligibility.hasToken());
	CHECK_FALSE(eligibility.srArmed());
	CHECK_FALSE(eligibility.fgArmed());
	CHECK_FALSE(eligibility.handoffEligible());
	CHECK_FALSE(eligibility.presentArmed());
	CHECK_FALSE(eligibility.presentStartEmitted());
	// The copy record goes with the token it was recorded under, so the next frame's first FG
	// tag rebuilds the copies rather than skipping them as the dropped frame's second call.
	CHECK(eligibility.copiesNeededFor(7));
	// A record under a fresh token cannot inherit the dropped frame's index, either.
	CHECK_FALSE(eligibility.srArmedAt(7));
}

TEST_CASE("releaseToken drops the token alone") {
	FrameEligibility eligibility = composed_frame(7);
	// The SR-only frame's evaluation consumes its token but leaves the tag records standing.
	eligibility.releaseToken();
	CHECK_FALSE(eligibility.hasToken());
	CHECK(eligibility.srArmed());
	CHECK(eligibility.fgArmed());
	CHECK(eligibility.tagSetComplete());
}
