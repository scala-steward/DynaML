package io.github.mandar2812.dynaml.repl

import ammonite.interp.{Interpreter, Preprocessor}
import ammonite.ops.Path
import ammonite.runtime.{Evaluator, Frame, Storage}
import ammonite.util.Util.{CodeSource, VersionedWrapperId}
import ammonite.util._

import scala.annotation.tailrec

class DynaMLInterpreter(
  printer: Printer, storage: Storage,
  basePredefs: Seq[PredefInfo], customPredefs: Seq[PredefInfo],
  // Allows you to set up additional "bridges" between the REPL
  // world and the outside world, by passing in the full name
  // of the `APIHolder` object that will hold the bridge and
  // the object that will be placed there. Needs to be passed
  // in as a callback rather than run manually later as these
  // bridges need to be in place *before* the predef starts
  // running, so you can use them predef to e.g. configure
  // the REPL before it starts
  extraBridges: Seq[(String, String, AnyRef)], wd: Path,
  colors: Ref[Colors], verboseOutput: Boolean = true,
  getFrame: () => Frame) extends Interpreter(
  printer, storage, basePredefs, customPredefs,
  extraBridges, wd, colors, verboseOutput, getFrame) {

  override val eval: DynaMLEvaluator = DynaMLEvaluator(headFrame)

  private var scriptImportCallback: Imports => Unit = handleImports

  def processCellBlocks(blocks: Seq[BlockData],
                        splittedScript: => Res[IndexedSeq[(String, Seq[String])]],
                        startingImports: Imports,
                        codeSource: CodeSource,
                        evaluate: (Preprocessor.Output, Name) => Res[((Iterator[String], Evaluated), Tag)],
                        autoImport: Boolean,
                        extraCode: String): Res[(ScriptOutput.Metadata, Iterator[String])] = synchronized {

    // we store the old value, because we will reassign this in the loop
    val outerScriptImportCallback = scriptImportCallback

    /**
      * Iterate over the blocks of a script keeping track of imports.
      *
      * We keep track of *both* the `scriptImports` as well as the `lastImports`
      * because we want to be able to make use of any import generated in the
      * script within its blocks, but at the end we only want to expose the
      * imports generated by the last block to who-ever loaded the script
      *
      * @param blocks the compilation block of the script, separated by `@`s.
      *               Each one is a tuple containing the leading whitespace and
      *               a sequence of statements in that block
      *
      * @param scriptImports the set of imports that apply to the current
      *                      compilation block, excluding that of the last
      *                      block that was processed since that is held
      *                      separately in `lastImports` and treated
      *                      specially
      *
      * @param lastImports the imports created by the last block that was processed;
      *                    only imports created by that
      *
      * @param wrapperIndex a counter providing the index of the current block, so
      *                     e.g. if `Foo.sc` has multiple blocks they can be named
      *                     `Foo_1` `Foo_2` etc.
      *
      * @param perBlockMetadata an accumulator for the processed metadata of each block
      *                         that is fed in
      */
    @tailrec def loop(blocks: Seq[BlockData],
                      scriptImports: Imports,
                      lastImports: Imports,
                      wrapperIndex: Int,
                      perBlockMetadata: List[ScriptOutput.BlockMetadata],
                      resultsCache: Iterator[String], tagCache: List[Tag])
    : Res[((ScriptOutput.Metadata, Iterator[String]), List[Tag])] = {
      if (blocks.isEmpty) {
        // No more blocks
        // if we have imports to pass to the upper layer we do that
        if (autoImport) outerScriptImportCallback(lastImports)
        Res.Success(((ScriptOutput.Metadata(perBlockMetadata), resultsCache), tagCache))
      } else {
        // imports from scripts loaded from this script block will end up in this buffer
        var nestedScriptImports = Imports()

        scriptImportCallback = { imports =>
          nestedScriptImports = nestedScriptImports ++ imports
        }

        // pretty printing results is disabled for scripts
        val indexedWrapperName = Interpreter.indexWrapperName(codeSource.wrapperName, wrapperIndex)


        def compileRunBlock(leadingSpaces: String, hookInfo: ImportHookInfo)
        : Res[((ScriptOutput.BlockMetadata, Iterator[String]), Tag)] = {
          val printSuffix = if (wrapperIndex == 1) "" else  " #" + wrapperIndex
          printer.info("Compiling " + codeSource.printablePath + printSuffix)
          for{
            processed <- compilerManager.preprocess(codeSource.fileName).transform(
              hookInfo.stmts,
              "",
              leadingSpaces,
              codeSource.pkgName,
              indexedWrapperName,
              scriptImports ++ hookInfo.imports,
              _ => "scala.Iterator[String]()",
              extraCode = extraCode,
              skipEmpty = false
            )

            ((iter, ev), tag) <- evaluate(processed, indexedWrapperName)
          } yield ((ScriptOutput.BlockMetadata(
            VersionedWrapperId(ev.wrapper.map(_.encoded).mkString("."), tag),
            leadingSpaces,
            hookInfo,
            ev.imports
          ), iter), tag)
        }


      //TODO: Understand how to load cached results along with class files.
      /*
        val cachedLoaded: Option[Res[((ScriptOutput.BlockMetadata, Iterator[String]), Tag)]] = for {
          (classFiles, blockMetadata) <- blocks.head
          tag <- tagCache.last
          // We don't care about the results of resolving the import hooks;
          // Assuming they still *can* be resolved, the `envHash` check will
          // ensure re-compile this block if the contents of any import hook
          // changes
          if resolveImportHooks(
            blockMetadata.hookInfo.trees,
            blockMetadata.hookInfo.stmts,
            codeSource
          ).isInstanceOf[Res.Success[_]]
        } yield {
          val envHash = Interpreter.cacheTag(evalClassloader.classpathHash)
          if (envHash != blockMetadata.id.tag.env) {
            compileRunBlock(blockMetadata.leadingSpaces, blockMetadata.hookInfo)
          } else{
            compilerManager.addToClasspath(classFiles)

            val cls = eval.loadClass(blockMetadata.id.wrapperPath, classFiles)
            val evaluated =
              try cls.map(eval.evalMain(_, evalClassloader))
              catch Evaluator.userCodeExceptionHandler

            evaluated.map(_ => ((blockMetadata, resultsCache), tag))
          }
        }
      */

        val res =
          for{
            allSplittedChunks <- splittedScript
            (leadingSpaces, stmts) = allSplittedChunks(wrapperIndex - 1)
            (hookStmts, importTrees) = parseImportHooks(codeSource, stmts)
            hookInfo <- resolveImportHooks(importTrees, hookStmts, codeSource)
            compile_res <- compileRunBlock(leadingSpaces, hookInfo)
          } yield compile_res

        res match {
          case Res.Success(((blockMetadata, it), t)) =>
            val last =
              blockMetadata.hookInfo.imports ++
                blockMetadata.finalImports ++
                nestedScriptImports

            loop(
              blocks.tail,
              scriptImports ++ last,
              last,
              wrapperIndex + 1,
              blockMetadata :: perBlockMetadata,
              it ++ resultsCache,
              t :: tagCache
            )

          case r: Res.Exit => r
          case r: Res.Failure => r
          case r: Res.Exception => r
          case Res.Skip =>
            loop(blocks.tail, scriptImports, lastImports, wrapperIndex + 1, perBlockMetadata, resultsCache, tagCache)

        }
      }
    }
    // wrapperIndex starts off as 1, so that consecutive wrappers can be named
    // Wrapper, Wrapper2, Wrapper3, Wrapper4, ...
    try {

      for(result <- loop(blocks, startingImports, Imports(), wrapperIndex = 1, List(), Iterator(), List()))
      // We build up `blockInfo` backwards, since it's a `List`, so reverse it
      // before giving it to the outside world
        yield (ScriptOutput.Metadata(result._1._1.blockInfo.reverse), result._1._2.toList.reverse.toIterator)
    } finally scriptImportCallback = outerScriptImportCallback
  }


  def evaluateCell(processed: Preprocessor.Output,
                   printer: Printer,
                   fileName: String,
                   indexedWrapperName: Name,
                   silent: Boolean = false,
                   incrementLine: () => Unit): Res[((Iterator[String], Evaluated), Tag)] = synchronized {
    for{
      _ <- Catching{ case e: ThreadDeath => Evaluator.interrupted(e) }
      (classFiles, newImports) <- compilerManager.compileClass(
        processed,
        printer,
        fileName
      )
      _ = incrementLine()
      res <- eval.processCell(
        classFiles,
        newImports,
        printer,
        indexedWrapperName,
        silent,
        evalClassloader
      )
    } yield (res, Tag("", ""))
  }

}
