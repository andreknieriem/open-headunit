# Connection lifecycle regressions

Run `python3 app/src/test/host-connection/run.py` after populating Gradle's Kotlin dependencies.
The runner extracts production lifecycle methods and uses JVM queues and latches to exercise
cancellation, ownership publication, full worker termination and USB open/close races.
Android framework and I/O endpoints are test doubles; no device is needed.

When the playback host suite is present, the runner also exercises the settings/publication
boundary with the real AapAudio and AudioDecoder owners. This optional integration check
retains standalone execution of the connection PR without a playback dependency.

## Manual execution

This is a **manual host tool**: neither Gradle test tasks nor Android CI invoke it.
Run it explicitly for lifecycle changes. It includes the Self launch suite, which can also be
run separately with `python3 app/src/test/host-connection/self-launch.py` after the first full run. Production member lookup uses exact declarations; a missing or renamed
member raises an error and must be updated together with its fixture. These tools complement JVM
unit tests; a Gradle pass alone does not mean these scenarios ran.
