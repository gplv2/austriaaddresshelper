package org.openstreetmap.josm.plugins.austriaaddresshelper;

import static org.openstreetmap.josm.tools.I18n.tr;
import static org.openstreetmap.josm.tools.I18n.trn;

import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.json.JsonReader;
import javax.swing.JOptionPane;

import org.openstreetmap.josm.actions.JosmAction;
import org.openstreetmap.josm.command.ChangeCommand;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.command.SequenceCommand;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.coor.conversion.DecimalDegreesCoordinateFormat;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.Notification;
import org.openstreetmap.josm.tools.HttpClient;
import org.openstreetmap.josm.tools.ImageProvider;
import org.openstreetmap.josm.tools.Shortcut;

/**
 * Created by tom on 02/08/15.
 */
public class AustriaAddressHelperAction extends JosmAction {
    static final String baseUrl = "https://bev-reverse-geocoder.thomaskonrad.at/reverse-geocode/json";
    static boolean addressTypeDialogCanceled = false;

    protected static HashMap<HashMap<String, String>, String> rememberedAddressTypeChoices = new HashMap<>();

    public AustriaAddressHelperAction() {
        super(tr("Fetch Address"), new ImageProvider("icon.png"), tr("Fetch Address"),
                Shortcut.registerShortcut("Fetch Address", tr("Fetch Address"),
                        KeyEvent.VK_A, Shortcut.CTRL_SHIFT), false, "fetchAddress",
                true);
    }

    @Override
    public void actionPerformed(ActionEvent event) {
        // Get the currently selected object
        final Collection<OsmPrimitive> sel = MainApplication.getLayerManager().getEditDataSet().getSelected();

        if (sel.size() != 1) {
            new Notification(tr("Austria Address Helper<br>Please select exactly one object."))
                    .setIcon(JOptionPane.ERROR_MESSAGE)
                    .show();

            return;
        }

        final List<Command> commands = new ArrayList<>();
        for (OsmPrimitive selectedObject : sel) {
        	OsmPrimitive newObject = loadAddress(selectedObject);
        	if(newObject != null){
        		commands.add(new ChangeCommand(selectedObject, newObject));
        	}
        }
        if (!commands.isEmpty()) {
            MainApplication.undoRedo.add(new SequenceCommand(trn("Add address", "Add addresses", commands.size()), commands));
        }
    }
    
    public static OsmPrimitive loadAddress(OsmPrimitive selectedObject){
        boolean noExceptionThrown = false;
        Exception exception = null;

        LatLon center = selectedObject.getBBox().getCenter();

        try {
            URL url = new URL(baseUrl
                    + "?lat=" + URLEncoder.encode(DecimalDegreesCoordinateFormat.INSTANCE.latToString(center), "UTF-8")
                    + "&lon=" + URLEncoder.encode(DecimalDegreesCoordinateFormat.INSTANCE.lonToString(center), "UTF-8")
                    + "&distance=30"
                    + "&limit=1"
                    + "&epsg=4326"
            );

            final JsonObject json;
            try (BufferedReader in = HttpClient.create(url)
                    .setReasonForRequest("JOSM Plugin Austria Address Helper")
                    .setHeader("User-Agent", "JOSM Plugin Austria Address Helper")
                    .connect()
                    .getContentReader();
                 JsonReader reader = Json.createReader(in)) {
                json = reader.readObject();
            }

            final JsonArray addressItems = json.getJsonArray("results");
            if (addressItems.size() > 0) {
                final JsonObject firstAddress = addressItems.getJsonObject(0);

                String country = "AT";
                String municipality = firstAddress.getString("municipality");
                String locality = firstAddress.getString("locality");
                String postcode = firstAddress.getString("postcode");
                String streetOrPlace;
                String houseNumber = firstAddress.getString("house_number");

                final OsmPrimitive newObject = selectedObject instanceof Node
                        ? new Node(((Node) selectedObject))
                        : selectedObject instanceof Way
                        ? new Way((Way) selectedObject)
                        : selectedObject instanceof Relation
                        ? new Relation((Relation) selectedObject)
                        : null;

                newObject.put("addr:country", country);

                // Some municipalities have a specific combination of postcode and street multiple times in several
                // localities. For example, the street "Feldgasse" in the municipality of Großebersdorf with the
                // the postcode 2203 exists four times, namely in the localities Eibesbrunn, Großebersdorf,
                // Manhartsbrunn, and Putzing. If this is the case, we need to set the "addr:city" tag to the value of
                // the locality and not the municipality so that the address is unique. If there is one such case in a
                // municipality, all addresses in the municipality have the locality in the addr:city tag (such
                // municipalities get the attribute "municipality_has_ambiguous_addresses").
                if (firstAddress.getBoolean("municipality_has_ambiguous_addresses")) {
                    newObject.put("addr:city", locality);
                } else {
                    newObject.put("addr:city", municipality);
                }

                newObject.put("addr:postcode", postcode);

                streetOrPlace = firstAddress.getString("street");

                // First remove addr:street and addr:place tags.
                newObject.remove("addr:street");
                newObject.remove("addr:place");

                // Decide whether the address type is 'street' or 'place'.
                if ((firstAddress.getString("address_type")).equals("place")) {
                    newObject.put("addr:place", streetOrPlace);
                } else if ((firstAddress.getString("address_type")).equals("street")) {
                    newObject.put("addr:street", streetOrPlace);
                } else {
                    // Get remembered choice or ask the user.
                    String addressType = getRememberedAddressTypeOrAsk(streetOrPlace, houseNumber, postcode, municipality);

                    // If the address type is neither "street" nor "place", show a warning and return.
                    if (addressType == null || !AddressTypeDialog.ALLOWED_ADDRESS_TYPES.contains(addressType)) {
                        new Notification(
                                "<strong>" + tr("Austria Address Helper") + "</strong><br />" +
                                        tr("No address type selected. Aborting.")
                        )
                                .setIcon(JOptionPane.WARNING_MESSAGE)
                                .setDuration(2500)
                                .show();

                        noExceptionThrown = true;
                        return null;
                    } else {
                        newObject.put("addr:" + addressType, streetOrPlace);
                    }
                }

                newObject.put("addr:housenumber", houseNumber);

                // Set the date of the data source.
                final String addressDate = json.getString("address_date");
                newObject.put("at_bev:addr_date", addressDate);

                // Set or add the address source.
                final String copyright = "Adressdaten: " + json.getString("copyright");

                // Add the data source to the changeset (not to the object because that can be changed easily).
                MainApplication.getLayerManager().getEditDataSet().addChangeSetTag("source", copyright);

                // Get the distance between the building center and the address coordinates.
                final double distanceToAddressCoordinates = firstAddress.getJsonNumber("distance").doubleValue();

                new Notification(
                        "<strong>" + tr("Austria Address Helper") + "</strong><br />" +
                        tr("Successfully added address to selected object:") + "<br />" +
                        encodeHTML(streetOrPlace) + " " + encodeHTML(houseNumber) + ", " + encodeHTML(postcode) + " " + encodeHTML(municipality) + " (" + encodeHTML(country) + ")<br/>" +
                        "<strong>" + tr("Distance between building center and address coordinates:") + "</strong> " +
                        new DecimalFormat("#.##").format(distanceToAddressCoordinates) + " " + tr("meters")
                )
                        .setIcon(JOptionPane.INFORMATION_MESSAGE)
                        .setDuration(2500)
                        .show();
                noExceptionThrown = true;
                return newObject;
            } else {
                new Notification(
                        "<strong>" + tr("Austria Address Helper") + "</strong><br />" +
                        tr("No address was found for this object.")
                )
                        .setIcon(JOptionPane.ERROR_MESSAGE)
                        .show();
            }

            noExceptionThrown = true;
        } catch (MalformedURLException e) {
            e.printStackTrace();
            exception = e;
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
            exception = e;
        } catch (IOException e) {
            e.printStackTrace();
            exception = e;
        } catch (NullPointerException e) {
            e.printStackTrace();
            exception = e;
        } finally {
            if (!noExceptionThrown) {
                new Notification(
                        "<strong>" + tr("Austria Address Helper") + "</strong>" +
                        tr("An unexpected exception occurred:") + exception.toString()
                )
                        .setIcon(JOptionPane.ERROR_MESSAGE)
                        .show();
            }
        }
        return null;

    }

    protected static String getRememberedAddressTypeOrAsk(String streetOrPlace, String houseNumber, String postcode, String city) {
        String addressType;

        // First, we'll look if there is a remembered choice for that place, postcode and city.
        String rememberedAddressType = getRememberedChoice(streetOrPlace, postcode, city);

        if (rememberedAddressType != null) {
            return rememberedAddressType;
        }

        // No remembered address type. Show the address type dialog and let the user decide.
        AddressTypeDialog dialog = new AddressTypeDialog(streetOrPlace, houseNumber, postcode, city);
        dialog.showDialog();

        // "OK" was not clicked
        if (dialog.getValue() != 1) {
            return null;
        }

        addressType = dialog.getAddressType();

        // The user has chosen to remember the address type, so store it for this session.
        if (dialog.rememberChoice() && addressType != null && AddressTypeDialog.ALLOWED_ADDRESS_TYPES.contains(addressType)) {
            // The user wants the choice to be remembered. Add the choice to the list of remembered choices.
            rememberedAddressTypeChoices.put(dialog.getRememberedChoice(), dialog.getAddressType());
        }

        return addressType;
    }

    @Override
    protected void updateEnabledState() {
        if (getLayerManager().getEditDataSet() == null) {
            setEnabled(false);
        } else {
            updateEnabledState(getLayerManager().getEditDataSet().getSelected());
        }
    }

    @Override
    protected void updateEnabledState(final Collection<? extends OsmPrimitive> selection) {
        // Enable it only if exactly one object is selected.
        setEnabled(selection != null && selection.size() == 1);
    }

    private static String encodeHTML(String s)
    {
        StringBuffer out = new StringBuffer();
        for(int i=0; i<s.length(); i++)
        {
            char c = s.charAt(i);
            if(c > 127 || c=='"' || c=='<' || c=='>')
            {
                out.append("&#"+(int)c+";");
            }
            else
            {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static String getRememberedChoice(String placeName, String postcode, String city) {
        HashMap<String, String> place = new HashMap<>();
        place.put("place_name", placeName);
        place.put("postcode", postcode);
        place.put("city", city);

        return rememberedAddressTypeChoices.get(place);
    }
}
