package validators

import okay.cardano.dev.{Scenarios, Workspace}
import org.scalatest.funsuite.AnyFunSuite

/** THE CONTRACTS FOR THE DEBUGGER: their debug builds written as okay-uplc's workspace (target/okay-uplc), and every
  * scenario under okay-uplc/scenarios run and checked against what it expects. `sbt ~test` keeps the workspace fresh;
  * `sbt "Test/runMain okay.cardano.dev.DevServer"` serves it to https://okay-uplc.okay-effects.workers.dev
  */
class Workbench extends AnyFunSuite {
    private val contracts = List(VestingContract, EscrowContract, MultisigContract, OneShotNftContract)

    test("the workspace is written") {
        val file = Workspace.write(contracts*)
        assert(java.nio.file.Files.size(file) > 0)
    }

    test("every scenario is as it expects") {
        val problems = Scenarios.verify(contracts*)
        assert(problems.isEmpty, problems.map((f, ps) => s"$f: ${ps.mkString("; ")}").mkString("\n"))
        assert(Scenarios.files().size >= 11)
    }
}
