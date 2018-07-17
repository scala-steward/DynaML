package io.github.mandar2812.dynaml.tensorflow.data

import ammonite.ops.Path
import com.sksamuel.scrimage.Image
import io.github.mandar2812.dynaml.pipes.{DataPipe, StreamDataPipe}
import io.github.mandar2812.dynaml.tensorflow.api.Api
import org.platanios.tensorflow.api.ops.io.data.Dataset
import org.platanios.tensorflow.api.implicits.helpers.DataTypeAuxToDataType.Aux
import org.platanios.tensorflow.api.implicits.helpers.OutputToTensor.Aux
import org.platanios.tensorflow.api.implicits.helpers.{DataTypeAuxToDataType, OutputToTensor}
import org.platanios.tensorflow.api.ops.io.data.Data.Aux
import org.platanios.tensorflow.api.ops.Function
import org.platanios.tensorflow.api.ops.io.data.{Data, Dataset}
import org.platanios.tensorflow.api._


private[tensorflow] object DataApi {

  def dataset[X](d: Iterable[X]) = new DataSet[X](d)

  def supervised_dataset[X, Y](inputs: Iterable[X], outputs: Iterable[Y]): SupervisedDataSet[X, Y] =
    new SupervisedDataSet[X, Y](new DataSet[X](inputs), new DataSet[Y](outputs))

  def supervised_dataset[X, Y](inputs: DataSet[X], outputs: DataSet[Y]): SupervisedDataSet[X, Y] =
    new SupervisedDataSet[X, Y](inputs, outputs)

  /**
    * Create a tensor from a collection of image data,
    * in a buffered manner.
    *
    * @param buff_size The size of the buffer (in number of images to load at once)
    * @param image_height The height, in pixels, of the image.
    * @param image_width The width, in pixels, of the image.
    * @param num_channels The number of channels in the image data.
    * @param coll The collection which holds the data for each image.
    * @param size The number of elements in the collection
    * */
  def create_image_tensor_buffered(
    buff_size: Int,
    image_to_bytes: DataPipe[Image, Array[Byte]],
    image_height: Int, image_width: Int, num_channels: Int)(
    coll: Iterable[Path], size: Int): Tensor = {

    val load_image = StreamDataPipe(DataPipe((p: Path) => Image.fromPath(p.toNIO)) > image_to_bytes)

    println()
    val tensor_splits = coll.grouped(buff_size).toIterable.zipWithIndex.map(splitAndIndex => {

      val split_seq = splitAndIndex._1.toStream

      val progress = math.round(10*splitAndIndex._2*buff_size*100.0/size)/10d

      print("Progress %:\t")
      pprint.pprintln(progress)

      Api.tensor_from_buffer(
        dtype = "UINT8", split_seq.length,
        image_height, image_width, num_channels)(load_image(split_seq).flatten.toArray)

    })

    Api.concatenate(tensor_splits.toSeq, axis = 0)
  }

  /**
    * Create a tensor from a collection of image data,
    * in a buffered manner.
    *
    * @param buff_size The size of the buffer (in number of images to load at once)
    * @param image_height The height, in pixels, of the image.
    * @param image_width The width, in pixels, of the image.
    * @param num_channels The number of channels in the image data.
    * @param coll The collection which holds the data for each image.
    * @param size The number of elements in the collection
    * */
  def create_image_tensor_buffered[Source](
    buff_size: Int, image_sources: Seq[Source],
    image_process: Map[Source, DataPipe[Image, Image]],
    images_to_bytes: DataPipe[Seq[Image], Array[Byte]],
    image_height: Int, image_width: Int, num_channels: Int)(
    coll: Iterable[Map[Source, Seq[Path]]], size: Int): Tensor = {

    val load_image = StreamDataPipe(DataPipe((images_map: Map[Source, Seq[Path]]) => {
      image_sources.map(source => {

        val images_for_source = images_map(source).map(p => image_process(source)(Image.fromPath(p.toNIO)))

        images_to_bytes(images_for_source)
      }).toArray.flatten
    }))

    println()
    val tensor_splits = coll.grouped(buff_size).toIterable.zipWithIndex.map(splitAndIndex => {

      val split_seq = splitAndIndex._1.toStream

      val progress = math.round(10*splitAndIndex._2*buff_size*100.0/size)/10

      print("Progress %:\t")
      pprint.pprintln(progress)

      Api.tensor_from_buffer(
        dtype = "UINT8", split_seq.length,
        image_height, image_width, num_channels)(
        load_image(split_seq).flatten.toArray)

    })

    Api.concatenate(tensor_splits.toSeq, axis = 0)
  }

}