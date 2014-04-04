/*
 * Copyright (c) 2014 Oculus Info Inc.
 * http://www.oculusinfo.com/
 *
 * Released under the MIT License.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies
 * of the Software, and to permit persons to whom the Software is furnished to do
 * so, subject to the following conditions:

 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.

 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

 /*global OpenLayers */
define(function (require) {
    "use strict";



    var Class        = require('../class'),
        AnnotationService = require('./AnnotationService'),
        TileTracker = require('../layer/TileTracker'),
        POPUP_WIDTH = '200px',
        POPUP_HEIGHT = '60px',
        defaultStyle,
        hoverStyle,
        selectStyle,
        temporaryStyle,
        AnnotationLayer,
        generatePopup,
        generateEditablePopup;


    generatePopup = function( feature ) {

        return "<input type='image' src='./images/edit-icon.png' width='17' height='17' style='position:absolute; right:0px; outline-width:0'>"+

                "<div style='padding-top:5px; padding-left:15px;'>"+
                    "<div style='width:"+POPUP_WIDTH+"; font-weight:bold; padding-bottom:10px;'>"+
                        feature.attributes.annotation.data.title +
                    "</div>"+
                    "<div style='padding-bottom:10px'>"+
                        "Priority: "+ feature.attributes.annotation.priority +
                    "</div>"+
                    "<div style='width:"+POPUP_WIDTH+"; height:"+POPUP_HEIGHT+"; overflow:auto; position:relative; right:0px'>"+
                        feature.attributes.annotation.data.comment +
                    "</div>"+
                "</div>";
    };

    generateEditablePopup = function( feature ) {

        var titleFill = "placeholder='Enter title'",
            priorityFill = "placeholder='Enter priority'",
            commentFill = "placeholder='Enter description'";

        if ( feature.attributes.annotation.data.title !== undefined ) {
            titleFill = "value='" + feature.attributes.annotation.data.title + "'";
        }

        if ( feature.attributes.annotation.priority !== undefined ) {
            priorityFill = "value='" + feature.attributes.annotation.priority + "'";
        }

        if ( feature.attributes.annotation.data.comment !== undefined ) {
            commentFill = "value='" + feature.attributes.annotation.data.comment + "'";
        }

        /*
        return  "<div style='padding-top:5px; padding-left:15px;'>"+
                    "<textarea id='annotation-text-area'>"+ commentFill +"</textarea> " +
                "</div>";
        */
        return  "<div id='main-div'>" +
                "<div style='padding-top:5px; padding-left:15px; position:relative;'>"+
                    "<div style='font-weight:bold; padding-bottom:10px;'>" +
                        "<label style='width:60px; float:left;'>Title: </label>" +
                        "<input style='width: calc(100% - 80px)' type='text' "+titleFill+">" +
                    "</div>"+
                    "<div style='font-weight:bold; padding-bottom:10px;'>" +
                        "<label style='width:60px; float:left;'>Priority: </label>" +
                        "<input style='width: calc(100% - 80px)' type='text' "+priorityFill+">" +
                    "</div>"+
                    "<div style='font-weight:bold;'> Description: </div>" +

                    "<div>"+
                          "<textarea style='max-width: 300px; max-height: 200px;' id='annotation-text-area'>"+ commentFill+"</textarea> "+
                    "</div>"+
                    "<button style='margin-top:10px; border-radius:5px; float:right; '>Cancel</button>"+
                    "<button style='margin-top:10px; border-radius:5px; float:right; '>Save</button>"+
                "</div>"+
                "</div>";

    };


    defaultStyle = new OpenLayers.Style({
        externalGraphic: "./images/marker-purple.png", //'http://www.openlayers.org/dev/img/marker.png',
        graphicWidth: 26, //21,
        graphicHeight: 26, //25,
        graphicYOffset: -25, //-24,
        'cursor': 'pointer'
    });

    hoverStyle = new OpenLayers.Style({
        externalGraphic: "./images/marker-green.png", //'http://www.openlayers.org/dev/img/marker-green.png',
        graphicWidth: 26, //21,
        graphicHeight: 26, //25,
        graphicYOffset: -25, //-24,
        'cursor': 'pointer'
    });

    selectStyle = new OpenLayers.Style({
        externalGraphic: "./images/marker-blue.png", //'http://www.openlayers.org/dev/img/marker-blue.png',
        graphicWidth: 26, //21,
        graphicHeight: 26, //25,
        graphicYOffset: -25, //-24,
        'cursor': 'pointer'
    });

    temporaryStyle = new OpenLayers.Style({
        display:"none"
    });



    AnnotationLayer = Class.extend({


        init: function (spec) {

            var that = this;

            this.addingFromServer = false;
            this.map = spec.map;
            this.layer = spec.layer;
            this.filters = spec.filters;
            this.tracker = new TileTracker( new AnnotationService( spec.layer ) );

            this.createLayer();

            // set callbacks
            this.map.on('zoomend', function() {
                that.addPointControl.activate();
                that.onMapUpdate();
            });

            this.map.on('panend', function() {
                that.onMapUpdate();
            });

            // trigger callback to draw first frame
            this.onMapUpdate();
        },


        onMapUpdate: function() {

            // determine all tiles in view
            var tiles = this.map.getTilesInView();

            this.tracker.filterAndRequestTiles( tiles, $.proxy( this.redrawAnnotations, this ) );
            
            this.redrawAnnotations();

        },


        transformToMapProj: function(latLon) {

            var fromProj = new OpenLayers.Projection("EPSG:4326"),
                toProj = this.map.projection;

            return new OpenLayers.LonLat( latLon.lon, latLon.lat ).transform( fromProj, toProj );
        },


        transformFromMapProj: function(latLon) {

            var fromProj = this.map.projection,
                toProj = new OpenLayers.Projection("EPSG:4326");

            return new OpenLayers.LonLat( latLon.lon, latLon.lat ).transform( fromProj, toProj );
        },

        redrawAnnotations: function() {

            var i,
                points =[],
                latlon,
                annotations = this.tracker.getDataObject(),
                annotation,
                feature,
                tilekey, binkey;


            // remove old annotations
            this._olLayer.destroyFeatures();

            // for each tile
            for (tilekey in annotations) {
                if (annotations.hasOwnProperty(tilekey)) {

                    // for each bin
                    for (binkey in annotations[tilekey]) {
                        if (annotations[tilekey].hasOwnProperty(binkey)) {

                            for (i=0; i<annotations[tilekey][binkey].length; i++) {

                                annotation = annotations[tilekey][binkey][i];

                                // convert to current map coordinate system
                                latlon = this.transformToMapProj( new OpenLayers.LonLat( annotation.x, annotation.y ) );

                                // create feature
                                feature = new OpenLayers.Feature.Vector( new OpenLayers.Geometry.Point( latlon.lon, latlon.lat) );

                                // store tilekey and binkey in annotation, for later reference
                                feature.attributes = { tilekey : tilekey,
                                                       binkey : binkey,
                                                       index : i,
                                                       annotation : annotation
                                };

                                // add marker
                                points.push( feature );
                            }
                        }
                    }
                }
            }

            this.addingFromServer = true;
            this._olLayer.addFeatures( points );
            this.addingFromServer = false;
        },


        createLayer: function() {

            var that = this;

            this._olLayer = new OpenLayers.Layer.Vector( "annotation-layer-"+this.layer, { 
                ratio: 2,
                styleMap: new OpenLayers.StyleMap({ 
                    "default" : defaultStyle,
                    "hover": hoverStyle,
                    "select": selectStyle,
                    "temporary": temporaryStyle
                }),
                eventListeners: {

                    "featureselected": function(e) {

                        that.addPointControl.deactivate();

                        var latlon = OpenLayers.LonLat.fromString(e.feature.geometry.toShortString()),
                            px,
                            size,
                            popup = new OpenLayers.Popup("marker-popup",
                                                     latlon,
                                                     null,
                                                     generateEditablePopup( e.feature ),
                                                     true);



                        popup.backgroundColor = '#222222';
                        popup.autoSize = true;
                        popup.panMapIfOutOfView = true;
                        e.feature.popup = popup;
                        that.map.map.olMap_.addPopup(popup, false);

                        latlon = OpenLayers.LonLat.fromString(e.feature.geometry.toShortString());
                        px = that.map.map.olMap_.getLayerPxFromViewPortPx( that.map.map.olMap_.getPixelFromLonLat(latlon) );
                        size = popup.size;
                        px.x -= size.w / 2;
                        px.y -= size.h + 35;
                        popup.moveTo( px );
                        popup.panIntoView();
                        popup.updateSize();

                        $('#annotation-text-area').resize( function(){
                            popup.updateSize();
                        });

                    },


                    "featureunselected": function(e) {

                        that.addPointControl.activate();
                        /*
                        that.map.olMap_.removePopup(e.feature.popup);
                        e.feature.popup.destroy();
                        e.feature.popup = null;
                        */
                    },


                    "featureadded": function(e) {

                        var latlon = that.transformFromMapProj( OpenLayers.LonLat.fromString( e.feature.geometry.toShortString() )),
                            annotation;

                        // check if was read from server, or added manually, if added manually, post to server
                        if ( that.addingFromServer === false ) {

                            annotation = {
                                x: latlon.lon,
                                y: latlon.lat,
                                priority : "P0",
                                data: {
                                    title: "Lorem ipsum dolor sit amet, consectetur adipisicing elit, sed do eiusmod temp",
                                    comment: "Lorem ipsum dolor sit amet, consectetur adipisicing elit, sed do eiusmod tempor incididunt ut labore et dolore magna aliqua. Ut enim ad minim veniam, quis nostrud exercitation ullamco laboris nisi ut aliquip ex ea commodo consequat. Duis aute irure dolor in reprehenderit in voluptate velit esse cillum dolore eu fugiat nulla pariatur. Excepteur sint occaecat cupidatat non proident, sunt in culpa qui officia deserunt mollit anim id est laborum."
                                }
                            };

                            e.feature.attributes.annotation = annotation;

                            // send POST request to write annotation to server
                            that.tracker.dataService.postRequest( "WRITE", annotation );
                        }

                    }

                }
            });

            this.map.addOLLayer( this._olLayer );

            this.addPointControl = new OpenLayers.Control.DrawFeature( this._olLayer, OpenLayers.Handler.Point );
            this.selectControl = new OpenLayers.Control.SelectFeature( this._olLayer, {
                clickout: true
            });

            this.dragControl = new OpenLayers.Control.DragFeature( this._olLayer, {
                onStart: function(feature, pixel) {
                    that.selectControl.clickFeature(feature);
                },

                onComplete: function(feature, pixel) {

                    var latlon = that.transformFromMapProj( OpenLayers.LonLat.fromString( feature.geometry.toShortString() )),
                        tilekey = feature.attributes.tilekey,
                        binkey = feature.attributes.binkey,
                        index = feature.attributes.index,
                        oldAnno, newAnno;

                    // get reference to old data
                    oldAnno = that.tracker.dataService.data[ tilekey ][ binkey ][ index ];

                    // copy object, add new location
                    newAnno = JSON.parse(JSON.stringify( oldAnno ));
                    newAnno.x = latlon.lon;
                    newAnno.y = latlon.lat;

                    // send POST request to write annotation to server
                    that.tracker.dataService.postRequest( "MODIFY", { "old" : oldAnno, "new" : newAnno } );

                }
                /*
                onDrag: function(feature, pixel){

                    var latlon = OpenLayers.LonLat.fromString(feature.geometry.toShortString()),
                        px = that.map.olMap_.getLayerPxFromViewPortPx( that.map.olMap_.getPixelFromLonLat(latlon) ),
                        size = feature.popup.size;
                    px.x -= size.w / 2;
                    px.y -= size.h + 25;
                    feature.popup.moveTo( px );
                }
                */
                                                   
            });
            this.highlightControl = new OpenLayers.Control.SelectFeature( this._olLayer, {
                hover: true,
                highlightOnly: true,
                renderIntent: "hover"

            });

            this.map.addOLControl(this.addPointControl);
            this.map.addOLControl(this.dragControl);
            this.map.addOLControl(this.highlightControl);
            this.map.addOLControl(this.selectControl);

            this.addPointControl.activate();
            this.dragControl.activate();
            this.highlightControl.activate();
            this.selectControl.activate();
        }

     });

    return AnnotationLayer;
});
