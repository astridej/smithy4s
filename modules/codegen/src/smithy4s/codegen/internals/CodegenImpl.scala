/*
 *  Copyright 2021-2024 Disney Streaming
 *
 *  Licensed under the Tomorrow Open Source Technology License, Version 1.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     https://disneystreaming.github.io/TOST-1.0.txt
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package smithy4s.codegen
package internals

import alloy.openapi._
import smithy4s.codegen.CodegenEntry.FromDisk
import smithy4s.codegen.CodegenEntry.FromMemory
import smithy4s.codegen.transformers._
import software.amazon.smithy.model.Model
import software.amazon.smithy.model.node.Node
import software.amazon.smithy.model.shapes.ModelSerializer

import scala.jdk.CollectionConverters._
import software.amazon.smithy.model.transform.ModelTransformer

private[codegen] object CodegenImpl { self =>

  def generate(args: CodegenArgs): CodegenResult = {
    val (classloader, model): (ClassLoader, Model) = internals.ModelLoader.load(
      args.specs.map(_.toIO).toSet,
      args.dependencies,
      args.repositories,
      withAwsTypeTransformer(args.transformers),
      args.discoverModels,
      args.localJars
    )

    val (scalaFiles, smithyResources) = if (!args.skipScala) {
      val codegenResult =
        CodegenImpl.generate(model, args.allowedNS, args.excludedNS)
      val scalaFiles = codegenResult.map { case (relPath, result) =>
        val fileName = result.name + ".scala"
        val scalaFile = (args.output / relPath / fileName)
        CodegenEntry.FromMemory(scalaFile, result.content)
      }
      val generatedNamespaces = codegenResult.map(_._2.namespace).distinct
      // when args.specs and generatedNamespaces are empty
      // we produce two files that are essentially empty
      val skipResource =
        args.skipResources || (args.specs.isEmpty && generatedNamespaces.isEmpty)
      val resources = if (!skipResource) {
        SmithyResources.produce(
          args.resourceOutput,
          args.specs,
          generatedNamespaces
        )
      } else List.empty[CodegenEntry]
      (scalaFiles, resources)
    } else (List.empty, List.empty)

    val openApiFiles = if (!args.skipOpenapi) {
      alloy.openapi.convert(model, args.allowedNS, classloader).map {
        case OpenApiConversionResult(_, serviceId, outputString) =>
          val name = serviceId.getNamespace() + "." + serviceId.getName()
          val openapiFile = (args.resourceOutput / (name + ".json"))
          CodegenEntry.FromMemory(openapiFile, outputString)
      }
    } else List.empty

    val protoFiles = if (!args.skipProto) {
      smithytranslate.proto3.SmithyToProtoCompiler.compile(model).map {
        renderedProto =>
          val protoFile = (args.resourceOutput / renderedProto.path)
          CodegenEntry.FromMemory(protoFile, renderedProto.contents)
      }
    } else List.empty

    CodegenResult(
      sources = scalaFiles,
      resources = openApiFiles ++ protoFiles ++ smithyResources
    )
  }

  def write(result: CodegenResult): Set[os.Path] = {
    def entryToDisk(entry: CodegenEntry): Unit = entry match {
      case FromMemory(path, content) =>
        os.write.over(path, content, createFolders = true)
        ()
      case FromDisk(path, sourceFile) =>
        os.copy.over(
          from = sourceFile,
          to = path,
          replaceExisting = true,
          createFolders = true
        )
    }

    val sourcesPaths = result.sources.map { e =>
      entryToDisk(e)
      e.toPath
    }
    val resourcesPaths = result.resources.map { e =>
      entryToDisk(e)
      e.toPath
    }

    (sourcesPaths ++ resourcesPaths).toSet
  }

  private[internals] def generate(
      model: Model,
      allowedNS: Option[Set[String]],
      excludedNS: Option[Set[String]]
  ): List[(os.RelPath, Renderer.Result)] = {
    val namespaces = model
      .shapes()
      .iterator()
      .asScala
      .map(_.getId().getNamespace())
      .toSet

    val reserved =
      Set(
        "alloy",
        "alloy.common",
        "alloy.proto",
        "smithy4s.api",
        "smithy4s.meta",
        "smithytranslate"
      )

    // Retrieving metadata that indicates what has already been generated by Smithy4s
    // in upstream jars.
    val alreadyGenerated: Set[String] = {
      val allGenerated = CodegenRecord
        .recordsFromModel(model)
        .flatMap { r =>
          r.namespaces
        }
      for (g <- allGenerated) {
        if (allGenerated.count(_ == g) > 1)
          throw new IllegalStateException(
            s"Multiple artifact manifests indicate containing generated code for namespace $g"
          )
      }
      allGenerated.toSet
    }

    val excluded = excludedNS.getOrElse(Set.empty)

    val filteredNamespaces = allowedNS match {
      case Some(allowedNamespaces) =>
        namespaces
          .filter(allowedNamespaces)
          .filterNot(excluded)
          .filterNot(alreadyGenerated)
      case None =>
        namespaces
          .filterNot(_.startsWith("aws."))
          .filterNot(_.startsWith("smithy."))
          .filterNot(ns => reserved.exists(ns.startsWith))
          .filterNot(excluded)
          .filterNot(alreadyGenerated)
    }

    filteredNamespaces.toList
      .map { ns => SmithyToIR(model, ns) }
      .flatMap { cu =>
        val amended = CollisionAvoidance(cu)
        Renderer(amended)
      }
      .map { result =>
        val relPath =
          os.RelPath(result.namespace.split('.').toIndexedSeq, ups = 0)
        (relPath, result)
      }

  }

  def dumpModel(args: DumpModelArgs): String = {
    val (_, model) = ModelLoader.load(
      args.specs.map(_.toIO).toSet,
      args.dependencies,
      args.repositories,
      withAwsTypeTransformer(args.transformers),
      discoverModels = false,
      args.localJars
    )
    val flattenedModel =
      ModelTransformer.create().flattenAndRemoveMixins(model)

    Node.prettyPrintJson(
      ModelSerializer.builder().build.serialize(flattenedModel)
    )
  }

  private def withAwsTypeTransformer(transformers: List[String]): List[String] =
    transformers :+ AwsConstraintsRemover.name :+ AwsStandardTypesTransformer.name :+ OpenEnumTransformer.name

}
