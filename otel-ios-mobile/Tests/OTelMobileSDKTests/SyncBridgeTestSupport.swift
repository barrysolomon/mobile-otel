import Dispatch

/// Runs a blocking sync API on a libdispatch thread so an async test never
/// parks its cooperative-executor thread on the API's internal semaphore.
///
/// Tests use this to exercise the sync `forceFlush` bridges (the OTel
/// protocol surface) without re-creating the executor-starvation deadlock
/// those bridges can cause when called from async contexts (issue #66):
/// the test body suspends (releasing its executor thread) while the bridge
/// blocks a global-queue thread, so the bridge's internal detached Task
/// always has an executor slot to run on.
func onDispatchThread<T: Sendable>(_ work: @escaping @Sendable () -> T) async -> T {
    await withCheckedContinuation { cont in
        DispatchQueue.global().async { cont.resume(returning: work()) }
    }
}
