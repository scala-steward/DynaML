/*
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
* */
package io.github.mandar2812.dynaml.optimization

/**
  * High level interface defining the
  * core functions of a global optimizer
  * @author mandar2812 datum 24/6/15.
  *
  * */
trait GlobalOptimizer[T <: GloballyOptimizable] extends ModelTuner[T, T]

object GlobalOptimizer {

  def prettyPrint(configuration: Map[String, Double]): String =
    configuration.foldLeft("""""")(
      (str, mapping) => str+""" """+mapping._1+""" = """+"%4f".format(mapping._2).toString+"\n"
    )
}
