// Device-free native harness, one of several TUs under native/test/.
//
// doctest v2.4.11 (MIT) vendored as native/test/doctest.h
// https://github.com/doctest/doctest/releases/tag/v2.4.11
// https://raw.githubusercontent.com/doctest/doctest/v2.4.11/doctest/doctest.h
//
// This is the harness's single implementation TU: DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
// must appear in exactly one translation unit of the binary, and every other test TU
// includes the header without the config macro.
#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include "doctest.h"
