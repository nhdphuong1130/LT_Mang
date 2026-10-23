package vn.edu.ltm.common.model;

import org.apache.commons.math3.filter.DefaultMeasurementModel;
import org.apache.commons.math3.filter.DefaultProcessModel;
import org.apache.commons.math3.filter.KalmanFilter;
import org.apache.commons.math3.linear.Array2DRowRealMatrix;
import org.apache.commons.math3.linear.ArrayRealVector;
import org.apache.commons.math3.linear.RealMatrix;

/** One-dimensional RSSI Kalman filter backed by Apache Commons Math. */
public final class ApacheRssiKalmanFilter {
    private final double processNoise;
    private final double measurementNoise;
    private KalmanFilter delegate;

    public ApacheRssiKalmanFilter(double processNoise, double measurementNoise) {
        if (!Double.isFinite(processNoise) || processNoise < 0.0) {
            throw new IllegalArgumentException("Process noise must be finite and non-negative");
        }
        if (!Double.isFinite(measurementNoise) || measurementNoise <= 0.0) {
            throw new IllegalArgumentException("Measurement noise must be finite and positive");
        }
        this.processNoise = processNoise;
        this.measurementNoise = measurementNoise;
    }

    public synchronized double update(double measurement) {
        if (!Double.isFinite(measurement)) {
            throw new IllegalArgumentException("RSSI measurement must be finite");
        }
        if (delegate == null) {
            delegate = createFilter(measurement);
            return measurement;
        }
        delegate.predict();
        delegate.correct(new double[]{measurement});
        return delegate.getStateEstimation()[0];
    }

    private KalmanFilter createFilter(double initialMeasurement) {
        RealMatrix transition = new Array2DRowRealMatrix(new double[][]{{1.0}}, false);
        RealMatrix measurement = new Array2DRowRealMatrix(new double[][]{{1.0}}, false);
        RealMatrix processCovariance = new Array2DRowRealMatrix(new double[][]{{processNoise}}, false);
        RealMatrix measurementCovariance = new Array2DRowRealMatrix(new double[][]{{measurementNoise}}, false);
        RealMatrix initialCovariance = new Array2DRowRealMatrix(new double[][]{{1.0}}, false);
        var processModel = new DefaultProcessModel(
                transition, null, processCovariance,
                new ArrayRealVector(new double[]{initialMeasurement}, false), initialCovariance);
        var measurementModel = new DefaultMeasurementModel(measurement, measurementCovariance);
        return new KalmanFilter(processModel, measurementModel);
    }
}
