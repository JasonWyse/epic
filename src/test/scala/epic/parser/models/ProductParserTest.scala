package epic.parser
package models

/*
 Copyright 2012 David Hall

 Licensed under the Apache License, Version 2.0 (the "License")
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
*/
import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.junit._


/**
 *
 * @author dlwh
 */
@RunWith(classOf[JUnitRunner])
class ProductParserTest extends ParserTestHarness with FunSuite {


  test("two parsers test") {
    val grammar = ParserTestHarness.refinedGrammar
    val product = Parser(ParserTestHarness.simpleParser.coreGrammar, new ProductChartFactory(IndexedSeq(grammar, grammar)))

    val rprod = evalParser(getTestTrees(), product)
    assert(rprod.f1 > 0.6, rprod)
  }
}
