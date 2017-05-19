package se.uu.it.easymr

import java.io.File

import scala.io.Source

import org.apache.spark.rdd.RDD

private[easymr] object EasyMapReduce {

  def mapLambda(
    imageName: String,
    command: String,
    inputMountPoint: String,
    outputMountPoint: String,
    record: String): String = {
    EasyMapReduce
      .mapLambda(
        imageName,
        command,
        inputMountPoint,
        outputMountPoint,
        Seq(record).iterator)
      .mkString
  }

  def mapLambda(
    imageName: String,
    command: String,
    inputMountPoint: String,
    outputMountPoint: String,
    records: Iterator[String]) = {

    //Create temporary files
    val inputFile = EasyFiles.writeToTmpFile(records)
    val outputFile = EasyFiles.createTmpFile

    //Run docker
    val docker = new EasyDocker
    docker.run(
      imageName,
      command,
      bindFiles = Seq(inputFile, outputFile),
      volumeFiles = Seq(new File(inputMountPoint), new File(outputMountPoint)))

    //Retrieve output
    val output = Source.fromFile(outputFile).getLines

    //Remove temporary files
    inputFile.delete
    outputFile.delete
    
    //Return output
    output

  }

}

/**
 * EasyMapReduce leverages the power of Docker and Spark to run and scale your serial tools 
 * in MapReduce fashion. The data goes from Spark through the Docker container, and back to Spark 
 * after being processed, via Unix files. Please make sure that the TMPDIR environment variable 
 * in the worker nodes points to a tmpfs to reduce overhead when running in production. To make sure
 * that the TMPDIR is properly set in each node you can use the "setExecutorEnv" method from the 
 * SparkConf class when initializing the SparkContext.
 * 
 *  @constructor
 *  @param rdd input RDD
 *  @param inputMountPoint mount point for the input chunk that is passed to the containers
 *  @param outputMountPoint mount point where the processed data is read back to Spark
 */
class EasyMapReduce(
    private val rdd: RDD[String],
    val inputMountPoint: String = "/input",
    val outputMountPoint: String = "/output") extends Serializable {

  def getRDD = rdd
  
  /**
   * It sets the mount point for the input chunk that is passed to the containers.
   * 
   * 	@constructor
   * 	@param inputMountPoint mount point for the input chunk that is passed to the containers
   */
  def setInputMountPoint(inputMountPoint: String) = {
    new EasyMapReduce(rdd, inputMountPoint, outputMountPoint)
  }
  
  /**
   * It sets the mount point where the processed data is read back to Spark.
   * 
   * @param outputMountPoint mount point where the processed data is read back to Spark
   */
  def setOutputMountPoint(outputMountPoint: String) = {
    new EasyMapReduce(rdd, inputMountPoint, outputMountPoint)
  }

  /**
   * It maps each RDD partition through a Docker container command. 
   * Data is mounted to the specified inputMountPoint and read back 
   * from the specified outputMountPoint. 
   * 
   * @param imageName a Docker image name available in each node
   * @param command a command to run in the Docker container, this should read from 
   * inputMountPoint and write back to outputMountPoint
   */
  def map(
    imageName: String,
    command: String) = {

    //Map partitions to avoid opening too many files
    val resRDD = rdd.mapPartitions(
      EasyMapReduce.mapLambda(imageName, command, inputMountPoint, outputMountPoint, _))
    new EasyMapReduce(resRDD, inputMountPoint, outputMountPoint)

  }

  /**
   * It reduces a RDD to a single String using a Docker container command. The command is applied first 
   * to each RDD partition, and then to couples of RDD records (that are concatenated with a new line
   * separator). Data is mounted to the specified inputMountPoint and read back from the specified 
   * outputMountPoint.
   * 
   * @param imageName a Docker image name available in each node
   * @param command a command to run in the Docker container, this should read from 
   * inputMountPoint and write back to outputMountPoint, and it should perform an
   * associative and commutative operation (for the parallelization to work)
   * 
   */
  def reduce(
    imageName: String,
    command: String) = {

    //First reduce within partitions
    val reducedPartitions = this.map(imageName, command).getRDD

    //Reduce
    reducedPartitions.reduce {
      case (rp1, rp2) =>
        EasyMapReduce.mapLambda(
          imageName, command, inputMountPoint, outputMountPoint, rp1 + "\n" + rp2)
    }

  }

}