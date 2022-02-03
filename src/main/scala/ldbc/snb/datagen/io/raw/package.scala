package ldbc.snb.datagen.io

import ldbc.snb.datagen.io.raw.csv.{CsvRecordOutputStream, CsvRowEncoder}
import ldbc.snb.datagen.io.raw.parquet.{ParquetRecordOutputStream, ParquetRowEncoder}
import ldbc.snb.datagen.model.EntityTraits
import ldbc.snb.datagen.util.GeneratorConfiguration
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FSDataOutputStream, FileSystem, Path}
import org.apache.hadoop.mapreduce.task.TaskAttemptContextImpl
import org.apache.hadoop.mapreduce._
import org.apache.parquet.hadoop.ParquetOutputFormat
import org.apache.parquet.hadoop.metadata.CompressionCodecName
import org.apache.spark.TaskContext
import org.apache.spark.sql.Encoder

import java.text.SimpleDateFormat
import java.util.{Date, Locale}

package object raw {
  trait RecordOutputStream[T] extends AutoCloseable {
    def write(t: T)
  }

  class RoundRobinOutputStream[T](outputStreams: Seq[RecordOutputStream[T]]) extends RecordOutputStream[T] {
    private var current = 0
    private val n       = outputStreams.length

    override def write(t: T): Unit = {
      outputStreams(current).write(t)
      current = (current + 1) % n
    }

    override def close(): Unit = for { os <- outputStreams } os.close()
  }

  sealed trait RawFormat
  case object Csv     extends RawFormat { override def toString = "csv"     }
  case object Parquet extends RawFormat { override def toString = "parquet" }

  case class RawSink(
      format: RawFormat,
      partitions: Option[Int] = None,
      conf: GeneratorConfiguration,
      oversizeFactor: Double = 1.0
  )

  class WriteContext(
      val taskContext: TaskContext,
      val taskAttemptContext: TaskAttemptContext,
      val hadoopConf: Configuration,
      val fileSystem: FileSystem
  )

  // See org.apache.parquet.hadoop.metadata.CompressionCodecName for other options. Note that not all are
  // supported by Spark.
  val DefaultParquetCompression = "snappy"

  private def fileOutputStream(fs: FileSystem, path: Path): FSDataOutputStream = fs.create(path, true, 131072)

  def csvRecordOutputStream[T <: Product: CsvRowEncoder](path: Path, writeContext: WriteContext) = {
    new CsvRecordOutputStream[T](fileOutputStream(writeContext.fileSystem, path))
  }

  def parquetRecordOutputStream[T <: Product: ParquetRowEncoder](path: Path, writeContext: WriteContext, options: Map[String, String]) = {
    new ParquetRecordOutputStream[T](path, writeContext.taskAttemptContext, options)
  }

  def recordOutputStream[T <: Product: EntityTraits: CsvRowEncoder: ParquetRowEncoder](sink: RawSink, writeContext: WriteContext): RecordOutputStream[T] = {
    val et                           = EntityTraits[T]
    val numFiles                     = Math.ceil(et.sizeFactor / sink.oversizeFactor).toLong
    val partitionId                  = writeContext.taskContext.partitionId()
    implicit val encoder: Encoder[T] = ParquetRowEncoder[T].encoder
    val entityPath                   = et.`type`.entityPath
    val (formatOutputStream, extension) = sink.format match {
      case Parquet => {
        val compression = CompressionCodecName.fromConf(DefaultParquetCompression)
        val options     = Map { ParquetOutputFormat.COMPRESSION -> compression.toString }
        (
          parquetRecordOutputStream(_: Path, writeContext, options),
          s"${compression.getExtension}.${sink.format}"
        )
      }
      case Csv => (csvRecordOutputStream(_: Path, writeContext), s".${sink.format.toString}")
      case x   => throw new UnsupportedOperationException(s"Raw serializer not implemented for format ${x}")
    }
    val files =
      for { i <- 0L until numFiles } yield new Path(
        s"${sink.conf.getOutputDir}/graphs/${sink.format.toString}/raw/composite-merged-fk/${entityPath}/part_${partitionId}_${i}${extension}"
      )
    new RoundRobinOutputStream(files.map(formatOutputStream))
  }

  def createNewWriteContext(hadoopConf: Configuration, fs: FileSystem) = {
    val jobIdInstant = new Date().getTime
    new WriteContext(TaskContext.get, getTaskAttemptContext(hadoopConf, TaskContext.get, jobIdInstant), hadoopConf, fs)
  }

  private[this] def getTaskAttemptContext(conf: Configuration, tc: TaskContext, jobIdInstant: Long): TaskAttemptContext = {

    val jobId         = createJobID(new Date(jobIdInstant), tc.stageId())
    val taskId        = new TaskID(jobId, TaskType.MAP, tc.partitionId())
    val taskAttemptId = new TaskAttemptID(taskId, tc.taskAttemptId().toInt & Integer.MAX_VALUE)

    // Set up the attempt context required to use in the output committer.
    {
      // Set up the configuration object
      conf.set("mapreduce.job.id", jobId.toString)
      conf.set("mapreduce.task.id", taskAttemptId.getTaskID.toString)
      conf.set("mapreduce.task.attempt.id", taskAttemptId.toString)
      conf.setBoolean("mapreduce.task.ismap", true)
      conf.setInt("mapreduce.task.partition", 0)
      new TaskAttemptContextImpl(conf, taskAttemptId)
    }
  }

  private[this] def createJobID(time: Date, id: Int): JobID = {
    val jobtrackerID = new SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(time)
    new JobID(jobtrackerID, id)
  }

  object instances extends csv.CsvRowEncoderInstances with parquet.ParquetRowEncoderInstances
}