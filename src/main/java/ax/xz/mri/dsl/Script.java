package ax.xz.mri.dsl;

/**
 * A user-authored experimental script — anything from a one-shot k-space
 * sweep to a 10k-iter adaptive Bayesian controller. Scripts own their own
 * lifecycle: write a {@code run(ctx)} method, drive whatever loop /
 * branching / parallelism you want, call ctx hooks to surface progress and
 * results to the studio (or to {@code stdout} when run standalone).
 *
 * <p>This is deliberately the simplest abstraction the studio can offer.
 * There is no {@code init} / {@code advance} / {@code finalise} contract;
 * there is no harness-owned iteration loop; there are no "passive" vs
 * "iterative" sub-types. A script is just a method. The harness gives it a
 * worker thread + a {@link ScriptContext} and asks it to please do
 * something useful.
 *
 * <p>Example — adaptive scan:
 * <pre>{@code
 *   import module ax.xz.mri;
 *   import static java.lang.Math.*;
 *
 *   class MyScan implements Script {
 *       public void run(ScriptContext ctx) throws InterruptedException {
 *           var state = new MyState();
 *           for (int i = 0; i < 1000; i++) {
 *               ctx.checkpoint();                       // stop button
 *               var trace = ctx.observationSource().run(segments(state), pulse(state));
 *               state = update(state, trace);
 *               ctx.status("iter " + i + " — rmse " + rmse(state));
 *               ctx.progress(i + 1, 1000);
 *               ctx.show(convergenceLine(state));
 *           }
 *           ctx.put("posteriorB", state.posteriorB);
 *           ctx.summary("converged at rmse " + rmse(state));
 *       }
 *
 *       void main() {
 *           NMRStudio.runScript(new MyScan());
 *       }
 *   }
 * }</pre>
 *
 * <p>"Procedure" survives as a {@link ax.xz.mri.project.ProcedureDocument
 * project-side category} — the kind of script the user files under
 * {@code procedures/} in their mri-project. The runtime contract is just
 * this interface; the document is metadata.
 */
@FunctionalInterface
public interface Script {

    /**
     * The script's body. Throws {@link InterruptedException} if the user
     * clicks Stop while the script is blocked inside
     * {@link ScriptContext#checkpoint()} or
     * {@link ax.xz.mri.service.procedure.ObservationSource#run}. Any other
     * throwable is reported back to the harness as a failure.
     */
    void run(ScriptContext ctx) throws InterruptedException;
}
