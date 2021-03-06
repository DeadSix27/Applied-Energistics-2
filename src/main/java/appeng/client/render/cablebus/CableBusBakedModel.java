/*
 * This file is part of Applied Energistics 2.
 * Copyright (c) 2013 - 2014, AlgorithmX2, All rights reserved.
 *
 * Applied Energistics 2 is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Applied Energistics 2 is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Applied Energistics 2.  If not, see <http://www.gnu.org/licenses/lgpl>.
 */

package appeng.client.render.cablebus;


import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.block.model.ItemCameraTransforms;
import net.minecraft.client.renderer.block.model.ItemOverrideList;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.MinecraftForgeClient;
import net.minecraftforge.common.property.IExtendedBlockState;

import appeng.api.util.AECableType;
import appeng.api.util.AEColor;
import appeng.block.networking.BlockCableBus;


public class CableBusBakedModel implements IBakedModel
{

	private final CableBuilder cableBuilder;

	private final FacadeBuilder facadeBuilder;

	private final Map<ResourceLocation, IBakedModel> partModels;

	private final TextureAtlasSprite particleTexture;

	CableBusBakedModel( CableBuilder cableBuilder, FacadeBuilder facadeBuilder, Map<ResourceLocation, IBakedModel> partModels, TextureAtlasSprite particleTexture )
	{
		this.cableBuilder = cableBuilder;
		this.facadeBuilder = facadeBuilder;
		this.partModels = partModels;
		this.particleTexture = particleTexture;
	}

	@Override
	public List<BakedQuad> getQuads( @Nullable IBlockState state, @Nullable EnumFacing side, long rand )
	{
		CableBusRenderState renderState = getRenderingState( state );

		if( renderState == null || side != null )
		{
			return Collections.emptyList();
		}

		BlockRenderLayer layer = MinecraftForgeClient.getRenderLayer();

		List<BakedQuad> quads = new ArrayList<>();

		// The core parts of the cable will only be rendered in the CUTOUT layer. TRANSLUCENT is used only for translucent facades further down below.
		if ( layer == BlockRenderLayer.CUTOUT )
		{
			// First, handle the cable at the center of the cable bus
			addCableQuads( renderState, quads );

			// Then handle attachments
			for( EnumFacing facing : EnumFacing.values() )
			{
				List<ResourceLocation> models = renderState.getAttachments().get( facing );
				if( models == null )
				{
					continue;
				}

				for( ResourceLocation model : models )
				{
					IBakedModel bakedModel = partModels.get( model );

					if( bakedModel == null )
					{
						throw new IllegalStateException( "Trying to use an unregistered part model: " + model );
					}

					List<BakedQuad> partQuads = bakedModel.getQuads( state, null, rand );

					// Rotate quads accordingly
					QuadRotator rotator = new QuadRotator();
					partQuads = rotator.rotateQuads( partQuads, facing, EnumFacing.UP );

					quads.addAll( partQuads );
				}
			}
		}

		facadeBuilder.addFacades(
				layer,
				renderState.getFacades(),
				renderState.getBoundingBoxes(),
				renderState.getAttachments().keySet(),
				rand,
				quads
		);

		return quads;
	}

	// Determines whether a cable is connected to exactly two sides that are opposite each other
	private static boolean isStraightLine( AECableType cableType, EnumMap<EnumFacing, AECableType> sides )
	{
		Iterator<EnumFacing> it = sides.keySet().iterator();
		if( !it.hasNext() )
		{
			return false; // No connections
		}
		EnumFacing firstSide = it.next();
		AECableType firstType = sides.get( firstSide );
		if( !it.hasNext() )
		{
			return false; // Only a single connection
		}
		if( firstSide.getOpposite() != it.next() )
		{
			return false; // Connected to two sides that are not opposite each other
		}
		if (it.hasNext()) {
			return false; // Must not have any other connection points
		}
		AECableType secondType = sides.get( firstSide.getOpposite() );

		// Certain cable types have restrictions on when they're rendered as a straight connection
		switch( cableType )
		{
			case GLASS:
				return firstType == AECableType.GLASS && secondType == AECableType.GLASS;
			case DENSE:
				return firstType == AECableType.DENSE && secondType == AECableType.DENSE;
		}

		return true;
	}

	private void addCableQuads( CableBusRenderState renderState, List<BakedQuad> quadsOut )
	{
		AECableType cableType = renderState.getCableType();
		if( cableType == AECableType.NONE )
		{
			return;
		}

		AEColor cableColor = renderState.getCableColor();
		EnumMap<EnumFacing, AECableType> connectionTypes = renderState.getConnectionTypes();

		// If the connection is straight, no busses are attached, and no covered core has been forced (in case of glass
		// cables), then render the cable as a simplified straight line.
		boolean noAttachments = renderState.getAttachments().isEmpty();
		if( isStraightLine( cableType, connectionTypes ) && noAttachments )
		{
			EnumFacing facing = connectionTypes.keySet().iterator().next();

			switch( cableType )
			{
				case GLASS:
					cableBuilder.addStraightGlassConnection( facing, cableColor, quadsOut );
					break;
				case COVERED:
					cableBuilder.addStraightCoveredConnection( facing, cableColor, quadsOut );
					break;
				case SMART:
					cableBuilder.addStraightSmartConnection( facing, cableColor, renderState.getChannelsOnSide().get( facing ), quadsOut );
					break;
				case DENSE:
					cableBuilder.addStraightDenseConnection( facing, cableColor, renderState.getChannelsOnSide().get( facing ), quadsOut );
					break;
			}

			return; // Don't render the other form of connection
		}

		cableBuilder.addCableCore( renderState.getCoreType(), cableColor, quadsOut );

		// Render all internal connections to attachments
		EnumMap<EnumFacing, Integer> attachmentConnections = renderState.getAttachmentConnections();
		for( EnumFacing facing : attachmentConnections.keySet() )
		{
			int distance = attachmentConnections.get( facing );
			int channels = renderState.getChannelsOnSide().get( facing );

			switch( cableType )
			{
				case GLASS:
					cableBuilder.addConstrainedGlassConnection( facing, cableColor, distance, quadsOut );
					break;
				case COVERED:
					cableBuilder.addConstrainedCoveredConnection( facing, cableColor, distance, quadsOut );
					break;
				case SMART:
					cableBuilder.addConstrainedSmartConnection( facing, cableColor, distance, channels, quadsOut );
					break;
				case DENSE:
					// Dense cables do not render connections to parts since none can be attached
					break;
			}
		}

		// Render all outgoing connections using the appropriate type
		for( EnumFacing facing : connectionTypes.keySet() )
		{
			AECableType connectionType = connectionTypes.get( facing );
			boolean cableBusAdjacent = renderState.getCableBusAdjacent().contains( facing );
			int channels = renderState.getChannelsOnSide().get( facing );

			switch( cableType )
			{
				case GLASS:
					cableBuilder.addGlassConnection( facing, cableColor, connectionType, cableBusAdjacent, quadsOut );
					break;
				case COVERED:
					cableBuilder.addCoveredConnection( facing, cableColor, connectionType, cableBusAdjacent, quadsOut );
					break;
				case SMART:
					cableBuilder.addSmartConnection( facing, cableColor, connectionType, cableBusAdjacent, channels, quadsOut );
					break;
				case DENSE:
					cableBuilder.addDenseConnection( facing, cableColor, connectionType, cableBusAdjacent, channels, quadsOut );
					break;
			}
		}
	}

	/**
	 * Gets a list of texture sprites appropriate for particles (digging, etc.) given the render state for a cable bus.
	 */
	public List<TextureAtlasSprite> getParticleTextures( CableBusRenderState renderState )
	{
		CableCoreType coreType = CableCoreType.fromCableType( renderState.getCableType() );
		AEColor cableColor = renderState.getCableColor();

		if( coreType != null )
		{
			return Collections.singletonList( cableBuilder.getCoreTexture( coreType, cableColor ) );
		}
		else
		{
			return Collections.emptyList();
		}

		// TODO: Add break particles even for the attachments, not just the cable

	}

	private static CableBusRenderState getRenderingState( IBlockState state )
	{
		if( state == null || !( state instanceof IExtendedBlockState ) )
		{
			return null;
		}

		IExtendedBlockState extendedBlockState = (IExtendedBlockState) state;
		return extendedBlockState.getValue( BlockCableBus.RENDER_STATE_PROPERTY );
	}

	@Override
	public boolean isAmbientOcclusion()
	{
		return false;
	}

	@Override
	public boolean isGui3d()
	{
		return false;
	}

	@Override
	public boolean isBuiltInRenderer()
	{
		return false;
	}

	@Override
	public TextureAtlasSprite getParticleTexture()
	{
		return particleTexture;
	}

	@Override
	public ItemCameraTransforms getItemCameraTransforms()
	{
		return ItemCameraTransforms.DEFAULT;
	}

	@Override
	public ItemOverrideList getOverrides()
	{
		return ItemOverrideList.NONE;
	}
}
