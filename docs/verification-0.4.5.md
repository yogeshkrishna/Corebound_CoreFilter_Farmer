# 0.4.5 image pose confidence

This patch retains the manual mapping, UI and shaft changes described in [0.4.4 verification](verification-0.4.4.md). Image pose rows now use the navigator's accepted camera confidence, rather than the input frame's provisional registration confidence. If a camera anchor is still unresolved, image offsets remain null even when that input frame has high confidence.

The manual archive/recovery check supplies a confident frame with an unregistered navigator snapshot and requires null saved offsets and zero accepted confidence. Existing bundle integrity, interruption recovery and no-touch manual mapping checks remain required, along with the normal Android build/lint and recorded-frame checks.

Package: com.corefilter.farmer, version code 9, version 0.4.5. Release builds use the original signing certificate. These checks do not establish complete live ceiling recognition or unattended farming success.
