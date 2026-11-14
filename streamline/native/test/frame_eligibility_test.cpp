#include "internal/state.h"

#include "doctest.h"

#include <cstdint>

using namespace mc_dlss;

namespace {

sl::FrameToken* fake_token(const std::uintptr_t value = 1) {
	return reinterpret_cast<sl::FrameToken*>(value);
}

FrameEligibility composed_frame(const uint32_t frameIndex) {
	FrameEligibility eligibility;
	eligibility.tokenSlot() = fake_token();
	eligibility.armSr(frameIndex);
	eligibility.armFg(frameIndex);
	return eligibility;
}

}

TEST_CASE("a fresh frame is eligible for nothing") {
	const FrameEligibility eligibility;
	CHECK_FALSE(eligibility.hasToken());
	CHECK_FALSE(eligibility.srArmed());
	CHECK_FALSE(eligibility.fgArmed());
	CHECK_FALSE(eligibility.tagSetComplete());
	CHECK_FALSE(eligibility.handoffEligible());
	CHECK_FALSE(eligibility.presentArmed());
	CHECK_FALSE(eligibility.presentStartPending());
	CHECK(eligibility.copiesNeededFor(0));
}

TEST_CASE("a handoff needs both sides under one index and the token they recorded under") {
	FrameEligibility srOnly;
	srOnly.tokenSlot() = fake_token();
	srOnly.armSr(7);
	CHECK_FALSE(srOnly.tagSetComplete());
	CHECK_FALSE(srOnly.handoffEligible());

	FrameEligibility mismatched;
	mismatched.tokenSlot() = fake_token();
	mismatched.armSr(7);
	mismatched.armFg(8);
	CHECK_FALSE(mismatched.tagSetComplete());
	CHECK_FALSE(mismatched.handoffEligible());

	FrameEligibility tokenless = composed_frame(7);
	tokenless.releaseToken();
	CHECK(tokenless.tagSetComplete());
	CHECK_FALSE(tokenless.handoffEligible());

	CHECK(composed_frame(7).handoffEligible());
}

TEST_CASE("the handoff consumes the tag set and keeps the token for the present bracket") {
	FrameEligibility eligibility = composed_frame(7);
	eligibility.consumeForHandoff();

	CHECK_FALSE(eligibility.srArmed());
	CHECK_FALSE(eligibility.fgArmed());
	CHECK_FALSE(eligibility.handoffEligible());
	CHECK(eligibility.hasToken());
	CHECK(eligibility.presentArmed());
	CHECK(eligibility.presentStartPending());
}

TEST_CASE("a partial re-tag never revives a consumed handoff") {
	FrameEligibility eligibility = composed_frame(7);
	eligibility.consumeForHandoff();

	eligibility.armSr(7);
	CHECK(eligibility.srArmed());
	CHECK_FALSE(eligibility.tagSetComplete());
	CHECK_FALSE(eligibility.handoffEligible());

	eligibility.armFg(7);
	CHECK(eligibility.handoffEligible());
}

TEST_CASE("the present bracket opens once and consumes the frame") {
	FrameEligibility eligibility = composed_frame(7);
	eligibility.consumeForHandoff();

	CHECK(eligibility.presentStartPending());
	eligibility.markPresentStartEmitted();
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
	eligibility.consumePresent();
	CHECK_FALSE(eligibility.presentArmed());
	CHECK_FALSE(eligibility.presentStartPending());
}

TEST_CASE("a retained token opens the present bracket without an FG handoff") {
	FrameEligibility eligibility;
	eligibility.tokenSlot() = fake_token();
	eligibility.armSr(7);
	CHECK(eligibility.presentStartPending());

	eligibility.markPresentStartEmitted();
	eligibility.consumePresent();
	CHECK_FALSE(eligibility.hasToken());
	CHECK_FALSE(eligibility.presentStartPending());
}

TEST_CASE("the orientation copies are recorded once per frame index") {
	FrameEligibility eligibility;
	CHECK(eligibility.copiesNeededFor(7));
	eligibility.recordCopies(7);
	CHECK_FALSE(eligibility.copiesNeededFor(7));
	CHECK(eligibility.copiesNeededFor(8));
}

TEST_CASE("srArmedAt tells the frame's two FG tag calls apart") {
	FrameEligibility eligibility;
	eligibility.tokenSlot() = fake_token();
	CHECK_FALSE(eligibility.srArmedAt(7));
	eligibility.armSr(7);
	CHECK(eligibility.srArmedAt(7));
	CHECK_FALSE(eligibility.srArmedAt(8));
}

TEST_CASE("the index oracle refuses until both sides recorded") {
	FrameEligibility eligibility;
	uint32_t srFrameIndex = 0xFFFFFFFF;
	uint32_t fgFrameIndex = 0xFFFFFFFF;
	CHECK_FALSE(eligibility.tagIndexes(&srFrameIndex, &fgFrameIndex));
	eligibility.armSr(7);
	CHECK_FALSE(eligibility.tagIndexes(&srFrameIndex, &fgFrameIndex));
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
	CHECK(eligibility.copiesNeededFor(7));
	CHECK_FALSE(eligibility.srArmedAt(7));
}

TEST_CASE("releaseToken drops the token alone") {
	FrameEligibility eligibility = composed_frame(7);
	eligibility.releaseToken();
	CHECK_FALSE(eligibility.hasToken());
	CHECK(eligibility.srArmed());
	CHECK(eligibility.fgArmed());
	CHECK(eligibility.tagSetComplete());
}
