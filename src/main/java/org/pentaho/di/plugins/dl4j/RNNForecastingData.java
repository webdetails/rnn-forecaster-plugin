package org.pentaho.di.plugins.dl4j;

/**
 * Created by pedro on 08-08-2016.
 */

import java.io.*;
import java.util.*;
import java.util.zip.GZIPInputStream;

import org.apache.commons.vfs2.FileObject;
import org.pentaho.di.core.exception.KettleStepException;
import org.pentaho.di.core.logging.LogChannelInterface;
import org.pentaho.di.core.row.RowDataUtil;
import org.pentaho.di.core.row.RowMetaInterface;
import org.pentaho.di.core.row.ValueMeta;
import org.pentaho.di.core.row.ValueMetaInterface;
import org.pentaho.di.core.variables.VariableSpace;
import org.pentaho.di.core.vfs.KettleVFS;
import org.pentaho.di.i18n.BaseMessages;
import org.pentaho.di.trans.step.BaseStepData;
import org.pentaho.di.trans.step.StepDataInterface;

import weka.classifiers.evaluation.NumericPrediction;
import weka.classifiers.timeseries.WekaForecaster;
import weka.classifiers.timeseries.eval.TSEvaluation;
import weka.core.Attribute;
import weka.core.DenseInstance;
import weka.core.Instance;
import weka.core.Instances;
import weka.core.Utils;
import weka.core.pmml.PMMLFactory;
import weka.core.pmml.PMMLModel;
import weka.core.xml.XStream;
import weka.filters.supervised.attribute.TSLagMaker;

import javax.swing.*;

/**
 * Holds temporary data and has routines for loading serialized models.
 *
 * @author Pedro Ferreira (pferreira{[at]}pentaho{[dot]}org)
 * @version 1.0
 */
public class RNNForecastingData extends BaseStepData implements StepDataInterface {

    /** For serialization */
    private static final long serialVersionUID = -8415151090793037265L;

    /**
     * some constants for various input field - attribute match/type problems
     */
    public static final int NO_MATCH = -1;
    public static final int TYPE_MISMATCH = -2;

    /** the output data format */
    protected RowMetaInterface m_outputRowMeta;

    /** holds values for instances constructed for prediction */
    private double[] m_vals = null;

    /**
     * Holds the actual Weka model (classifier, clusterer or PMML) used by this
     * copy of the step
     */
    protected RNNForecastingModel m_model;

    /** used to map attribute indices to incoming field indices */
    private int[] m_mappingIndexes;

    public RNNForecastingData() {
        super();
    }

    /**
     * Set the model for this copy of the step to use
     *
     * @param model the model to use
     */
    public void setModel(RNNForecastingModel model) {
        m_model = model;
    }

    /**
     * Get the model that this copy of the step is using
     *
     * @return the model that this copy of the step is using
     */
    public RNNForecastingModel getModel() {
        return m_model;
    }

    /**
     * Get the meta data for the output format
     *
     * @return a <code>RowMetaInterface</code> value
     */
    public RowMetaInterface getOutputRowMeta() {
        return m_outputRowMeta;
    }

    /**
     * Set the meta data for the output format
     *
     * @param rmi a <code>RowMetaInterface</code> value
     */
    public void setOutputRowMeta(RowMetaInterface rmi) {
        m_outputRowMeta = rmi;
    }

    /**
     * Finds a mapping between the attributes that a Weka model has been trained
     * with and the incoming Kettle row format. Returns an array of indices, where
     * the element at index 0 of the array is the index of the Kettle field that
     * corresponds to the first attribute in the Instances structure, the element
     * at index 1 is the index of the Kettle fields that corresponds to the second
     * attribute, ...
     *
     * @param header the Instances header
     * @param inputRowMeta the meta data for the incoming rows
     * @param log the log to use
     */
    public void mapIncomingRowMetaData(Instances header,
                                       RowMetaInterface inputRowMeta, LogChannelInterface log) {
        m_mappingIndexes = RNNForecastingData.findMappings(header, inputRowMeta);
    }

    public static boolean modelFileExists(String modelFile, VariableSpace space)
            throws Exception {

        modelFile = space.environmentSubstitute(modelFile);
        FileObject modelF = KettleVFS.getFileObject(modelFile);

        return modelF.exists();
    }

    /**
     * Loads a serialized model. Models can either be binary serialized Java
     * objects, objects deep-serialized to xml, or PMML.
     *
     * @param modelFile a <code>File</code> value
     * @return the model
     * @throws Exception if there is a problem laoding the model.
     */
    public static RNNForecastingModel loadSerializedModel(String modelFile,
                                                       LogChannelInterface log, VariableSpace space) throws Exception {
        Object model = null;
        Instances header = null;
        File sFile = null;

        modelFile = space.environmentSubstitute(modelFile);
        FileObject modelF = KettleVFS.getFileObject(modelFile);

        if (!modelF.exists()) {
            throw new Exception(
                    BaseMessages
                            .getString(
                                    RNNForecastingMeta.PKG,
                                    "RNNForecasting.Error.NonExistentModelFile", space.environmentSubstitute(modelFile))); //$NON-NLS-1$
        }

        sFile = new File(modelFile);
        InputStream is = KettleVFS.getInputStream(modelF);
        BufferedInputStream buff = new BufferedInputStream(is);
        InputStream stream = buff;
        ObjectInputStream oi = new ObjectInputStream(new FileInputStream(sFile));

        model = oi.readObject();

        // try and grab the header
        header = (Instances) oi.readObject();

        oi.close();

        RNNForecastingModel wfm = RNNForecastingModel.createScorer(model);
        wfm.setHeader(header);
        wfm.loadBaseModel();
        wfm.loadSerializedState();

        return wfm;
    }

    /**
     * Finds a mapping between the attributes that a Weka model has been trained
     * with and the incoming Kettle row format. Returns an array of indices, where
     * the element at index 0 of the array is the index of the Kettle field that
     * corresponds to the first attribute in the Instances structure, the element
     * at index 1 is the index of the Kettle fields that corresponds to the second
     * attribute, ...
     *
     * @param header the Instances header
     * @param inputRowMeta the meta data for the incoming rows
     * @return the mapping as an array of integer indices
     */
    public static int[] findMappings(Instances header,
                                     RowMetaInterface inputRowMeta) {
        // Instances header = m_model.getHeader();
        int[] mappingIndexes = new int[header.numAttributes()];

        HashMap<String, Integer> inputFieldLookup = new HashMap<String, Integer>();
        for (int i = 0; i < inputRowMeta.size(); i++) {
            ValueMetaInterface inField = inputRowMeta.getValueMeta(i);
            inputFieldLookup.put(inField.getName(), Integer.valueOf(i));
        }

        // check each attribute in the header against what is incoming
        for (int i = 0; i < header.numAttributes(); i++) {
            Attribute temp = header.attribute(i);
            String attName = temp.name();

            // look for a matching name
            Integer matchIndex = inputFieldLookup.get(attName);
            boolean ok = false;
            int status = NO_MATCH;
            if (matchIndex != null) {
                // check for type compatibility
                ValueMetaInterface tempField = inputRowMeta.getValueMeta(matchIndex
                        .intValue());
                if (tempField.isNumeric() || tempField.isBoolean()) {
                    if (temp.isNumeric()) {
                        ok = true;
                        status = 0;
                    } else {
                        status = TYPE_MISMATCH;
                    }
                } else if (tempField.isString()) {
                    if (temp.isNominal() || temp.isString()) {
                        ok = true;
                        status = 0;
                        // All we can assume is that this input field is ok.
                        // Since we wont know what the possible values are
                        // until the data is pumping throug, we will defer
                        // the matching of legal values until then
                    } else {
                        status = TYPE_MISMATCH;
                    }
                } else if (tempField.isDate()) {
                    if(temp.isDate()) {
                        ok = true;
                        status = 0;
                    } else {
                        status = TYPE_MISMATCH;
                    }
                } else {
                    // any other type is a mismatch (might be able to do
                    // something with dates at some stage)
                    status = TYPE_MISMATCH;
                }
            }
            if (ok) {
                mappingIndexes[i] = matchIndex.intValue();
            } else {
                // mark this attribute as missing or type mismatch
                mappingIndexes[i] = status;
            }
        }
        return mappingIndexes;
    }

    /**
     * Generates a batch of predictions (more specifically, an array of output
     * rows containing all input Kettle fields plus new fields that hold the
     * prediction(s)) for each incoming Kettle row given a Weka model.
     *
     * @param inputMeta the meta data for the incoming rows
     * @param outputMeta the meta data for the output rows
     * @param inputRows the values of the incoming row
     * @param meta meta data for this step
     * @return a Kettle row containing all incoming fields along with new ones
     *         that hold the prediction(s)
     * @exception Exception if an error occurs
     */
    public Object[][] generateForecast(RowMetaInterface inputMeta,
                                       RowMetaInterface outputMeta, List<Object[]> inputRows,
                                       RNNForecastingMeta meta) throws Exception {
        RNNForecastingModel model = getModel(); // copy of the model for this copy of the step
        model.getHeader().setClassIndex(getClassIndexes(model)[0]);

        Instances batch = new Instances(model.getHeader(), inputRows.size());
        // loop through rows and make each one an Instance object, part of Instances
        for (Object[] r : inputRows) {
            Instance inst = constructInstance(inputMeta, r, m_mappingIndexes, model, true);
            batch.add(inst);
        }

        Instances primeData = new Instances(model.getHeader());
        Instances overlayData = new Instances(model.getHeader());
        for (Instance instance : batch) {
            if (!instance.classIsMissing()) {
                primeData.add(instance);
            } else {
                overlayData.add(instance);
            }
        }

        int[] targetIndexes = getTargetColumns(model, inputMeta);
        int dateIndex = 0;
        for (int i = 0; i < inputMeta.getFieldNames().length; i++) {
            if (batch.attribute(i).isDate())
                dateIndex = i;
        }

        int modelDateIndex = 0;
        for (int i = 0 ; i < model.getHeader().numAttributes(); i++) {
            if (model.getHeader().attribute(i).isDate()) {
                modelDateIndex = i;
                break;
            }
        }

        if (meta.getClearPreviousState()) {
            model.clearPreviousState();
        }

        // Prime forecaster with historical enough data to create lagged variables
        model.primeForecaster(primeData);

        // Generate output rows. Operations differ if we are using overlay data
        Object[][] result = null;
        if (overlayData.numInstances() == 0) {
            result = generateOutputRows(model, inputRows, outputMeta, meta, primeData, dateIndex,
                    modelDateIndex, targetIndexes);
        } else {
            result = generateOverlayOutputRows(model, inputRows, outputMeta, primeData, overlayData,
                    targetIndexes);
        }
        return result;
    }

    private Object[][] generateOutputRows(RNNForecastingModel model, List<Object[]> inputRows,
                                          RowMetaInterface outputMeta, RNNForecastingMeta meta, Instances primeData,
                                          int dateIndex, int modelDateIndex, int[] targetIndexes) throws Exception {

        int stepsToForecast = new Integer(meta.getStepsToForecast());

        // dates must be generated *before* forecasting
        List<String> dates = model.getForecastDates(stepsToForecast, primeData.lastInstance(), modelDateIndex);
        List<List<NumericPrediction>> forecast = model.forecast(stepsToForecast);

        // Output rows
        Object[][] result = new Object[stepsToForecast + primeData.numInstances()][model.getHeader().numAttributes()];

        // First copy the priming data to the output rows
        for (int i = 0; i < primeData.numInstances(); i++) {
            result[i] = RowDataUtil.resizeArray(inputRows.get(i), outputMeta.size());
        }

        // Now generate prediction rows
        for (int i = 0; i < stepsToForecast; i++) {
            result[i + primeData.numInstances()] = RowDataUtil.allocateRowData(outputMeta.size());
            ValueMetaInterface newVM = new ValueMeta("string", ValueMetaInterface.TYPE_STRING);

            List<NumericPrediction> prediction = forecast.get(i);

            double[] predPerClass = classPredictions(prediction);
            List<Double> predsDouble = new ArrayList<>(predPerClass.length);
            List<String> preds = new ArrayList<>(predPerClass.length);

            for (int j = 0; j < predPerClass.length; j++) {
                predsDouble.add(predPerClass[j]);
                preds.add(predsDouble.get(j).toString());
                result[i + inputRows.size()][targetIndexes[j]] = newVM.convertToBinaryStringStorageType(preds.get(j));
            }

            result[i + primeData.numInstances()][dateIndex] = newVM.convertToBinaryStringStorageType(dates.get(i));
        }

        return result;
    }

    private Object[][] generateOverlayOutputRows(RNNForecastingModel model, List<Object[]> inputRows,
                                          RowMetaInterface outputMeta, Instances primeData,
                                          Instances overlayData, int[] targetIndexes) throws Exception {

        overlayData.setClassIndex(-1);

        int stepsToForecast = overlayData.numInstances();
        List<List<NumericPrediction>> forecast = model.forecast(stepsToForecast, overlayData);

        // Output rows
        Object[][] result = new Object[stepsToForecast + primeData.numInstances()][model.getHeader().numAttributes()];

        // First copy all the input data to the output rows
        for (int i = 0; i < inputRows.size(); i++) {
            result[i] = RowDataUtil.resizeArray(inputRows.get(i), outputMeta.size());
        }

        // Now populate the targets with predictions
        for (int i = 0; i < overlayData.numInstances(); i++) {
            ValueMetaInterface newVM = new ValueMeta("string", ValueMetaInterface.TYPE_STRING);

            List<NumericPrediction> prediction = forecast.get(i);

            double[] predPerClass = classPredictions(prediction);
            List<Double> predsDouble = new ArrayList<>(predPerClass.length);
            List<String> preds = new ArrayList<>(predPerClass.length);
            for (int j = 0; j < predPerClass.length; j++) {
                predsDouble.add(predPerClass[j]);
                preds.add(predsDouble.get(j).toString());
                result[i + primeData.numInstances()][targetIndexes[j]] = newVM.convertToBinaryStringStorageType(preds.get(j));
            }
        }

        return result;
    }

    public Object[][] genwerateForecast(RowMetaInterface inputMeta,
                                          RowMetaInterface outputMeta, List<Object[]> inputRows,
                                          RNNForecastingMeta meta) throws Exception {
        int[] mappingIndexes = m_mappingIndexes;
        RNNForecastingModel model = getModel(); // copy of the model for this copy of the step

        Instances batch = new Instances(model.getHeader(), inputRows.size());
        // loop through rows and make each one an Instance object, part of Instances
        for (Object[] r : inputRows) {
            Instance inst = constructInstance(inputMeta, r, mappingIndexes, model, true);
            batch.add(inst);
        }

        Instances primeData = new Instances(model.getHeader());
        Instances overlayData = new Instances(model.getHeader());
        for (Instance instance : batch) {
            if (!instance.classIsMissing()) {
                primeData.add(instance);
            } else {
                overlayData.add(instance);
            }
        }

        model.primeForecaster(primeData);

        int[] targetIndexes = getTargetColumns(model, inputMeta);
        int dateIndex = 0;
        for (int i = 0; i < inputMeta.getFieldNames().length; i++) {
            if (batch.attribute(i).isDate())
                dateIndex = i;
        }

        int modelDateIndex = 0;
        for (int i = 0 ; i < model.getHeader().numAttributes(); i++) {
            if (model.getHeader().attribute(i).isDate()) {
                modelDateIndex = i;
                break;
            }
        }

        int stepsToForecast = new Integer(meta.getStepsToForecast());
        List<List<NumericPrediction>> forecast = model.forecast(stepsToForecast);
        if (!(overlayData.size() == 0)) {
            stepsToForecast = overlayData.numInstances();
            forecast = model.forecast(stepsToForecast, overlayData);
        }


        List<String> dates = model.getForecastDates(stepsToForecast, batch.lastInstance(), modelDateIndex);

        // Output rows
        Object[][] result = new Object[stepsToForecast + primeData.numInstances()][model.getHeader().numAttributes()];

        // First copy the input data to the output rows
        for (int i = 0; i < primeData.numInstances(); i++) {
            result[i] = RowDataUtil.resizeArray(inputRows.get(i), outputMeta.size());
        }
        // Now generate prediction rows
        for (int i = 0; i < stepsToForecast; i++) {
            result[i + primeData.numInstances()] = RowDataUtil.allocateRowData(outputMeta.size());
            ValueMetaInterface newVM = new ValueMeta("string", ValueMetaInterface.TYPE_STRING);

            List<NumericPrediction> prediction = forecast.get(i);

            double[] predPerClass = classPredictions(prediction);
            List<Double> predsDouble = new ArrayList<>(predPerClass.length);
            List<String> preds = new ArrayList<>(predPerClass.length);
            for (int j = 0; j < predPerClass.length; j++) {
                predsDouble.add(predPerClass[j]);
                preds.add(predsDouble.get(j).toString());
                result[i + inputRows.size()][targetIndexes[j]] = newVM.convertToBinaryStringStorageType(preds.get(j));
            }
            result[i + primeData.numInstances()][dateIndex] = newVM.convertToBinaryStringStorageType(dates.get(i));
        }

        return result;
    }

//    private Object[][] putPredictionsWithOverlay (int stepsToForecast, Instances primeData, Object[][] result) {
//        for (int i = 0; i < stepsToForecast; i++) {
//            result[i + primeData.numInstances()] = RowDataUtil.allocateRowData(outputMeta.size());
//            ValueMetaInterface newVM = new ValueMeta("string", ValueMetaInterface.TYPE_STRING);
//
//            List<NumericPrediction> prediction = forecast.get(i);
//
//            double[] predPerClass = classPredictions(prediction);
//            List<Double> predsDouble = new ArrayList<>(predPerClass.length);
//            List<String> preds = new ArrayList<>(predPerClass.length);
//            for (int j = 0; j < predPerClass.length; j++) {
//                predsDouble.add(predPerClass[j]);
//                preds.add(predsDouble.get(j).toString());
//                result[i + primeData.numInstances()][targetIndexes[j]] = newVM.convertToBinaryStringStorageType(preds.get(j));
//            }
//            result[i + primeData.numClasses()][dateIndex] = newVM.convertToBinaryStringStorageType(dates.get(i));
//        }
//    }

    private int[] getClassIndexes(RNNForecastingModel model) {
        List<String> targetFieldNames = model.getTargetFieldNames();
        int[] classIndexes = new int[targetFieldNames.size()];
        for (int i = 0; i < targetFieldNames.size(); i++) {
            for (int j = 0; j < targetFieldNames.size(); j++) {
                if (model.getHeader().attribute(i).name().equalsIgnoreCase(targetFieldNames.get(j))) {
                    classIndexes[i] = j;
                }
            }
        }
        return classIndexes;
    }

    private int[] getTargetColumns(RNNForecastingModel model, RowMetaInterface inputMeta) {
        List<String> targetFieldNames = model.getTargetFieldNames();
        int[] targetIndexes = new int[targetFieldNames.size()];
        String[] fieldNames = inputMeta.getFieldNames();
        for (int i = 0; i < targetFieldNames.size(); i++) {
            for (int j = 0; j < fieldNames.length; j++) {
                if (targetFieldNames.get(i).equalsIgnoreCase(fieldNames[j])) {
                    targetIndexes[i] = j;
                }
            }
        }
        return targetIndexes;
    }

    private double[] classPredictions(List<NumericPrediction> predAtStep) {
        double[] predPerClass = new double[predAtStep.size()];

        for (int i = 0; i < predAtStep.size(); i++) {
            NumericPrediction predAtTarget = predAtStep.get(i);
            predPerClass[i] = predAtTarget.predicted();
        }

        return predPerClass;
    }

//    public Object[][] generatePredictions(RowMetaInterface inputMeta,
//                                          RowMetaInterface outputMeta, List<Object[]> inputRows,
//                                          RNNForecastingMeta meta) throws Exception {
//
//        int[] mappingIndexes = m_mappingIndexes;
//        RNNForecastingModel model = getModel(); // copy of the model for this copy of
//        // the step
//        boolean outputProbs = meta.getOutputProbabilities();
//        boolean supervised = model.isSupervisedLearningModel();
//
//        Attribute classAtt = null;
//        if (supervised) {
//            classAtt = model.getHeader().classAttribute();
//        }
//
//        Instances batch = new Instances(model.getHeader(), inputRows.size());
//        for (Object[] r : inputRows) {
//            Instance inst = constructInstance(inputMeta, r, mappingIndexes, model,
//                    true);
//            batch.add(inst);
//        }
//
//        double[][] preds = model.distributionsForInstances(batch);
//
//        Object[][] result = new Object[preds.length][];
//        for (int i = 0; i < preds.length; i++) {
//            // First copy the input data to the new result...
//            Object[] resultRow = RowDataUtil.resizeArray(inputRows.get(i),
//                    outputMeta.size());
//            int index = inputMeta.size();
//
//            double[] prediction = preds[i];
//
//            if (prediction.length == 1 || !outputProbs) {
//                if (supervised) {
//                    if (classAtt.isNumeric()) {
//                        Double newVal = new Double(prediction[0]);
//                        resultRow[index++] = newVal;
//                    } else {
//                        int maxProb = Utils.maxIndex(prediction);
//                        if (prediction[maxProb] > 0) {
//                            String newVal = classAtt.value(maxProb);
//                            resultRow[index++] = newVal;
//                        } else {
//                            String newVal = BaseMessages.getString(RNNForecastingMeta.PKG,
//                                    "RNNForecastingData.Message.UnableToPredict"); //$NON-NLS-1$
//                            resultRow[index++] = newVal;
//                        }
//                    }
//                } else {
//                    int maxProb = Utils.maxIndex(prediction);
//                    if (prediction[maxProb] > 0) {
//                        Double newVal = new Double(maxProb);
//                        resultRow[index++] = newVal;
//                    } else {
//                        String newVal = BaseMessages.getString(RNNForecastingMeta.PKG,
//                                "RNNForecastingData.Message.UnableToPredictCluster"); //$NON-NLS-1$
//                        resultRow[index++] = newVal;
//                    }
//                }
//            } else {
//                // output probability distribution
//                for (int j = 0; j < prediction.length; j++) {
//                    Double newVal = new Double(prediction[j]);
//                    resultRow[index++] = newVal;
//                }
//            }
//
//            result[i] = resultRow;
//        }
//
//        return result;
//    }

    /**
     * Generates a prediction (more specifically, an output row containing all
     * input Kettle fields plus new fields that hold the prediction(s)) for an
     * incoming Kettle row given a Weka model.
     *
     * @param inputMeta the meta data for the incoming rows
     * @param outputMeta the meta data for the output rows
     * @param inputRow the values of the incoming row
     * @param meta meta data for this step
     * @return a Kettle row containing all incoming fields along with new ones
     *         that hold the prediction(s)
     * @exception Exception if an error occurs
     */
//    public Object[] generatePrediction(RowMetaInterface inputMeta,
//                                       RowMetaInterface outputMeta, Object[] inputRow, RNNForecastingMeta meta)
//            throws Exception {
//
//        int[] mappingIndexes = m_mappingIndexes;
//        RNNForecastingModel model = getModel();
//        boolean outputProbs = meta.getOutputProbabilities();
//        boolean supervised = model.isSupervisedLearningModel();
//
//        Attribute classAtt = null;
//        if (supervised) {
//            classAtt = model.getHeader().classAttribute();
//        }
//
//        // need to construct an Instance to represent this
//        // input row
//        Instance toScore = constructInstance(inputMeta, inputRow, mappingIndexes,
//                model, false);
//        double[] prediction = model.distributionForInstance(toScore);
//
//        // Update the model??
//        if (meta.getUpdateIncrementalModel() && model.isUpdateableModel()
//                && !toScore.isMissing(toScore.classIndex())) {
//            model.update(toScore);
//        }
//        // First copy the input data to the new result...
//        Object[] resultRow = RowDataUtil.resizeArray(inputRow, outputMeta.size());
//        int index = inputMeta.size();
//
//        // output for numeric class or discrete class value
//        if (prediction.length == 1 || !outputProbs) {
//            if (supervised) {
//                if (classAtt.isNumeric()) {
//                    Double newVal = new Double(prediction[0]);
//                    resultRow[index++] = newVal;
//                } else {
//                    int maxProb = Utils.maxIndex(prediction);
//                    if (prediction[maxProb] > 0) {
//                        String newVal = classAtt.value(maxProb);
//                        resultRow[index++] = newVal;
//                    } else {
//                        String newVal = BaseMessages.getString(RNNForecastingMeta.PKG,
//                                "RNNForecastingData.Message.UnableToPredict"); //$NON-NLS-1$
//                        resultRow[index++] = newVal;
//                    }
//                }
//            } else {
//                int maxProb = Utils.maxIndex(prediction);
//                if (prediction[maxProb] > 0) {
//                    Double newVal = new Double(maxProb);
//                    resultRow[index++] = newVal;
//                } else {
//                    String newVal = BaseMessages.getString(RNNForecastingMeta.PKG,
//                            "RNNForecastingData.Message.UnableToPredictCluster"); //$NON-NLS-1$
//                    resultRow[index++] = newVal;
//                }
//            }
//        } else {
//            // output probability distribution
//            for (int i = 0; i < prediction.length; i++) {
//                Double newVal = new Double(prediction[i]);
//                resultRow[index++] = newVal;
//            }
//        }
//
//        return resultRow;
//    }

    /**
     * Helper method that constructs an Instance to input to the Weka model based
     * on incoming Kettle fields and pre-constructed attribute-to-field mapping
     * data.
     *
     * @param inputMeta a <code>RowMetaInterface</code> value
     * @param inputRow an <code>Object</code> value
     * @param mappingIndexes an <code>int</code> value
     * @param model a <code>RNNForecastingModel</code> value
     * @return an <code>Instance</code> value
     */
    private Instance constructInstance(RowMetaInterface inputMeta,
                                       Object[] inputRow, int[] mappingIndexes, RNNForecastingModel model,
                                       boolean freshVector) {

        Instances header = model.getHeader();

        // Re-use this array (unless told otherwise) to avoid an object creation
        if (m_vals == null || freshVector) {
            m_vals = new double[header.numAttributes()];
        }

        for (int i = 0; i < header.numAttributes(); i++) {

            if (mappingIndexes[i] >= 0) {
                try {
                    Object inputVal = inputRow[mappingIndexes[i]];

                    Attribute temp = header.attribute(i);
                    ValueMetaInterface tempField = inputMeta
                            .getValueMeta(mappingIndexes[i]);
                    int fieldType = tempField.getType();

                    // Check for missing value (null or empty string)
                    if (tempField.isNull(inputVal)) {
                        m_vals[i] = Utils.missingValue();
                        continue;
                    }

                    switch (temp.type()) {
                        case Attribute.DATE: {
                            String s = tempField.getString(inputVal);
                            m_vals[i] = temp.parseDate(s);
                            break;
                        }
                        case Attribute.NUMERIC: {
                            if (fieldType == ValueMetaInterface.TYPE_BOOLEAN) {
                                Boolean b = tempField.getBoolean(inputVal);
                                if (b.booleanValue()) {
                                    m_vals[i] = 1.0;
                                } else {
                                    m_vals[i] = 0.0;
                                }
                            } else if (fieldType == ValueMetaInterface.TYPE_INTEGER) {
                                Long t = tempField.getInteger(inputVal);
                                m_vals[i] = t.longValue();
                            } else {
                                Double n = tempField.getNumber(inputVal);
                                m_vals[i] = n.doubleValue();
                            }
                        }
                        break;
                        case Attribute.NOMINAL: {
                            String s = tempField.getString(inputVal);
                            // now need to look for this value in the attribute
                            // in order to get the correct index
                            int index = temp.indexOfValue(s);
                            if (index < 0) {
                                // set to missing value
                                m_vals[i] = Utils.missingValue();
                            } else {
                                m_vals[i] = index;
                            }
                        }
                        break;
                        case Attribute.STRING: {
                            String s = tempField.getString(inputVal);
                            // Set the attribute in the header to contain just this string value
                            temp.setStringValue(s);
                            m_vals[i] = 0.0;
                            break;
                        }
                        default:
                            m_vals[i] = Utils.missingValue();
                    }
                } catch (Exception e) {
                    m_vals[i] = Utils.missingValue();
                }
            } else {
                // set to missing value
                m_vals[i] = Utils.missingValue();
            }
        }

        Instance newInst = new DenseInstance(1.0, m_vals);
        newInst.setDataset(header);
        return newInst;
    }
}
