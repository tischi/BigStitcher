package gui.popup;

import javax.swing.JComponent;
import javax.swing.JMenu;

import gui.StitchingResultsSettable;
import gui.popup.CalculatePCPopup.Method;
import mpicbg.spim.data.generic.AbstractSpimData;
import mpicbg.spim.data.generic.sequence.AbstractSequenceDescription;
import spim.fiji.spimdata.explorer.ExplorerWindow;
import spim.fiji.spimdata.explorer.popup.ExplorerWindowSetable;
import spim.fiji.spimdata.stitchingresults.StitchingResults;

public class CalculatePCPopupExpertBatch extends JMenu implements ExplorerWindowSetable, StitchingResultsSettable
{

	final CalculatePCPopup phaseCorrSimple;
	final CalculatePCPopup phaseCorr;
	final CalculatePCPopup lucasKanade;
	final CalculatePCPopup interestPoint;
	boolean wizardMode;

	public CalculatePCPopupExpertBatch( String description, boolean wizardMode )
	{
		super( description );

		this.wizardMode = wizardMode;

		if (!wizardMode)
			phaseCorrSimple = new CalculatePCPopup( "Phase Correlation", true, Method.PHASECORRELATION, wizardMode );
		else
			phaseCorrSimple = null;
			
		phaseCorr = new CalculatePCPopup( wizardMode ? "Phase Correlation" : "Phase Correlation (expert)", false, Method.PHASECORRELATION, wizardMode );
		lucasKanade = new CalculatePCPopup( "Lucas-Kanade", false, Method.LUCASKANADE, wizardMode );
		interestPoint = new CalculatePCPopup( "Lucas-Kanade", false, Method.LUCASKANADE, wizardMode );

		if(!wizardMode)
			this.add(phaseCorrSimple);
		this.add( phaseCorr );
		this.add( lucasKanade );
		this.add( interestPoint );
	}

	@Override
	public void setStitchingResults(StitchingResults res)
	{
		if (!wizardMode)
			this.phaseCorrSimple.setStitchingResults( res );
		this.phaseCorr.setStitchingResults( res );
		this.lucasKanade.setStitchingResults( res );
		this.interestPoint.setStitchingResults( res );
	}

	@Override
	public JComponent setExplorerWindow(
			ExplorerWindow< ? extends AbstractSpimData< ? extends AbstractSequenceDescription< ?, ?, ? > >, ? > panel)
	{
		if (!wizardMode)
			this.phaseCorrSimple.setExplorerWindow( panel );
		this.phaseCorr.setExplorerWindow( panel );
		this.lucasKanade.setExplorerWindow( panel );
		this.interestPoint.setExplorerWindow( panel );
		return this;
	}

}
